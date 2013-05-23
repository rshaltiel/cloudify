/*******************************************************************************
 * Copyright (c) 2013 GigaSpaces Technologies Ltd. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.cloudifysource.rest.deploy;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import org.apache.commons.io.FileUtils;
import org.cloudifysource.dsl.Service;
import org.cloudifysource.dsl.internal.CloudifyConstants;
import org.cloudifysource.dsl.internal.DSLApplicationCompilatioResult;
import org.cloudifysource.dsl.internal.DSLReader;
import org.cloudifysource.dsl.internal.DSLUtils;
import org.cloudifysource.dsl.internal.packaging.FileAppender;
import org.cloudifysource.dsl.internal.packaging.Packager;
import org.cloudifysource.dsl.rest.request.InstallApplicationRequest;
import org.cloudifysource.dsl.rest.request.InstallServiceRequest;
import org.cloudifysource.dsl.utils.ServiceUtils;
import org.cloudifysource.rest.controllers.DeploymentsController;
import org.cloudifysource.rest.controllers.RestErrorException;

import com.j_spaces.kernel.Environment;

/**
 * A Runnable implementation that executes the deployment logic of an application.
 * 
 * @author adaml
 *
 */
public class ApplicationDeployerRunnable implements Runnable {
	private static final int SERVICE_INSTANCE_STARTUP_TIMEOUT_MINUTES = 60;

	private static final java.util.logging.Logger logger = java.util.logging.Logger
			.getLogger(ApplicationDeployerRunnable.class.getName());

	private final DeploymentsController controller;
	private final InstallApplicationRequest request;
	private final String applicationName;
	private final File overridesFile;
	private final List<Service> services;
	private final DSLApplicationCompilatioResult result;

	/**
	 * Constructor.
	 * @param controller
	 * 		installation requests are delegated to this controller.
	 * @param request
	 * 		the install application request.
	 * @param result
	 * 		the application compilation result.
	 * @param services
	 * 		the list of services.
	 * @param overridesFile
	 * 		application overrides file.
	 * @throws RestErrorException
	 */
	public ApplicationDeployerRunnable(final DeploymentsController controller, 
						final InstallApplicationRequest request, 
						final DSLApplicationCompilatioResult result, 
						final List<Service> services,
						final File overridesFile) throws RestErrorException {
		this.request = request;
		this.controller = controller;
		this.result = result;
		this.services = services;
		this.applicationName = request.getApplicationName();
		this.overridesFile = overridesFile;
	}

	@Override
	public void run() {
		logger.fine("Installing application " + applicationName + " with the following services: " + services);

		final boolean asyncInstallPossible = isAsyncInstallPossibleForApplication();
		logger.info("Async install setting is " + asyncInstallPossible);
		try {
			installServices(asyncInstallPossible);
		} catch (final IOException e) {
			e.printStackTrace();
		}
	}

	private void installServices(final boolean async)
			throws IOException {

		final File appDir = result.getApplicationDir();
		
		logger.info("Installing services for application: " + applicationName 
				+ ". Async install: " + async + ". Number of services: " + this.services.size());
		for (final Service service : services) {
			logger.info("Installing service: " + service.getName() + " for application: " + applicationName);
			service.getCustomProperties().put("usmJarPath",
					Environment.getHomeDirectory() + "/lib/platform/usm");

			final String serviceName = service.getName();
			final String absolutePUName = ServiceUtils.getAbsolutePUName(
					applicationName, serviceName);
			final File serviceDirectory = new File(appDir, serviceName);

			// scan for service cloud configuration file
			final File cloudConfiguration = new File(serviceDirectory,
					CloudifyConstants.SERVICE_CLOUD_CONFIGURATION_FILE_NAME);

			boolean found = false;

			try {
				// this will actually create an empty props file.
				final FileAppender appender = new FileAppender("finalPropsFile.properties");
				final LinkedHashMap<File, String> filesToAppend = new LinkedHashMap<File, String>();

				// first add the application properties file. least important overrides.
				// lookup application properties file
				final File applicationPropertiesFile =
						DSLReader.findDefaultDSLFileIfExists(DSLUtils.APPLICATION_PROPERTIES_FILE_NAME, appDir);
				filesToAppend.put(applicationPropertiesFile, "Application Properties File");
				// add the service properties file, second level overrides.
				// lookup service properties file
				final String propertiesFileName = DSLUtils.getPropertiesFileName(serviceDirectory,
						DSLUtils.SERVICE_DSL_FILE_NAME_SUFFIX);
				final File servicePropertiesFile = new File(serviceDirectory, propertiesFileName);
				filesToAppend.put(servicePropertiesFile, "Service Properties File");
				// lookup overrides file
				File actualOverridesFile = overridesFile;
				if (actualOverridesFile == null) {
					// when using the CLI, the application overrides file is inside the directory
					actualOverridesFile =
							DSLReader.findDefaultDSLFileIfExists(DSLUtils.APPLICATION_OVERRIDES_FILE_NAME, appDir);
				}
				// add the overrides file given in the command or via REST, most important overrides.
				filesToAppend.put(actualOverridesFile, "Overrides Properties File");
				/*
				 * name the merged properties file as the original properties file. this will allow all properties to be
				 * available by anyone who parses the default properties file. (like Lifecycle scripts)
				 */
				appender.appendAll(servicePropertiesFile, filesToAppend);

				// Pack the folder and name it absolutePuName
				final File packedFile = Packager.pack(service, 
													serviceDirectory, 
													absolutePUName, 
													null);
				result.getApplicationFile().delete();
				packedFile.deleteOnExit();
				// Deployment will be done using the service's absolute PU name.
				final InstallServiceRequest installServiceReq = createInstallServiceRequest(
						cloudConfiguration, 
						applicationPropertiesFile,
						packedFile);
				final String appName = this.request.getApplicationName();
	
				controller.installServiceInternal(appName, serviceName, 
												installServiceReq, 
												packedFile, 
												null /* service overrides file */, 
												cloudConfiguration,
												applicationPropertiesFile);
				try {
					FileUtils.deleteDirectory(packedFile.getParentFile());
				} catch (final IOException ioe) {
					// sometimes this delete fails. Not sure why. Maybe deploy
					// is async?
					logger.warning("Failed to delete temporary directory: "
							+ packedFile.getParentFile());
				}

				if (!async) {
					logger.info("Waiting for instance of service: " + serviceName 
							+ " of application: "	+ applicationName);
					final boolean instanceFound = controller
							.waitForServiceInstance(applicationName,
									serviceName,
									SERVICE_INSTANCE_STARTUP_TIMEOUT_MINUTES,
									TimeUnit.MINUTES);
					if (!instanceFound) {
						throw new TimeoutException(
								"Service "
										+ serviceName
										+ " of application "
										+ applicationName
										+ " was installed, but no instance of the service has started after "
										+ SERVICE_INSTANCE_STARTUP_TIMEOUT_MINUTES
										+ " minutes.");
					}
					logger.info("Found instance of: " + serviceName);
				}

				found = true;
				logger.fine("service " + service + " deployed.");
			} catch (final Exception e) {
				logger.log(
						Level.SEVERE,
						"Failed to install service: "
								+ serviceName
								+ " of application: "
								+ applicationName
								+ ". Application installation will halt. "
								+ "Some services may already have started, and should be shutdown manually. Error was: "
								+ e.getMessage(), e);
				return;
			}

			if (!found) {
				logger.severe("Failed to find an instance of service: "
						+ serviceName
						+ " while installing application "
						+ applicationName
						+ ". Application installation will stop. Some services may have been installed!");
				return;
			}
		}
		FileUtils.deleteDirectory(appDir);
	}

	InstallServiceRequest createInstallServiceRequest(
			final File cloudConfiguration,
			final File applicationPropertiesFile, 
			final File packedFile) {
		
		final InstallServiceRequest installServiceReq = new InstallServiceRequest();
		installServiceReq.setCloudOverridesUploadKey(request.getCloudOverridesUploadKey());
		installServiceReq.setAuthGroups(this.request.getAuthGroups());
		installServiceReq.setDebugAll(this.request.isDebugAll());
		installServiceReq.setDebugEvents(this.request.getDebugEvents());
		installServiceReq.setDebugMode(this.request.getDebugModeString());
		installServiceReq.setSelfHealing(this.request.isSelfHealing());
		installServiceReq.setServiceFileName(packedFile.getName());
		installServiceReq.setTimeoutInMillis(0);

		return installServiceReq;
	}

	/**
	 *
	 * @return true if all services have Lifecycle events.
	 */
	public boolean isAsyncInstallPossibleForApplication() {
		// check if all services are USM
		for (final Service service : this.services) {
			if (service.getLifecycle() == null) {
				return false;
			}
		}

		return true;
	}
}

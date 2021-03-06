/*
 * Copyright 2018 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.frontend;

import com.google.cloud.tools.jib.JibLogger;
import com.google.cloud.tools.jib.configuration.credentials.Credential;
import com.google.cloud.tools.jib.configuration.credentials.CredentialRetriever;
import com.google.cloud.tools.jib.configuration.credentials.CredentialRetriever.CredentialRetrievalException;
import com.google.cloud.tools.jib.image.ImageReference;
import com.google.cloud.tools.jib.registry.credentials.DockerConfigCredentialRetriever;
import com.google.cloud.tools.jib.registry.credentials.DockerCredentialHelperFactory;
import com.google.cloud.tools.jib.registry.credentials.DockerCredentialHelperNotFoundException;
import com.google.cloud.tools.jib.registry.credentials.NonexistentServerUrlDockerCredentialHelperException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Static factories for various {@link CredentialRetriever}s. */
public class CredentialRetrieverFactory {

  /**
   * Defines common credential helpers to use as defaults. Maps from registry suffix to credential
   * helper suffix.
   */
  private static final ImmutableMap<String, String> COMMON_CREDENTIAL_HELPERS =
      ImmutableMap.of("gcr.io", "gcr", "amazonaws.com", "ecr-login");

  /**
   * Creates a new {@link CredentialRetrieverFactory} for an image.
   *
   * @param imageReference the image the credential are for
   * @param logger a logger for logging
   * @return a new {@link CredentialRetrieverFactory}
   */
  public static CredentialRetrieverFactory forImage(
      ImageReference imageReference, JibLogger logger) {
    return new CredentialRetrieverFactory(imageReference, logger);
  }

  private final JibLogger logger;
  private ImageReference imageReference;

  private CredentialRetrieverFactory(ImageReference imageReference, JibLogger logger) {
    this.imageReference = imageReference;
    this.logger = logger;
  }

  /**
   * Sets the image reference the {@link CredentialRetriever}s should retrieve credentials for.
   *
   * @param imageReference the image reference
   * @return this
   */
  public CredentialRetrieverFactory setImageReference(ImageReference imageReference) {
    this.imageReference = imageReference;
    return this;
  }

  /**
   * Creates a new {@link CredentialRetriever} that returns a known {@link Credential}.
   *
   * @param credential the known credential
   * @param credentialSource the source of the credentials (for logging)
   * @return a new {@link CredentialRetriever}
   */
  public CredentialRetriever known(Credential credential, String credentialSource) {
    return () -> {
      logGotCredentialsFrom(credentialSource);
      return credential;
    };
  }

  /**
   * Creates a new {@link CredentialRetriever} for retrieving credentials via a Docker credential
   * helper, such as {@code docker-credential-gcr}.
   *
   * @param credentialHelper the credential helper executable
   * @return a new {@link CredentialRetriever}
   */
  public CredentialRetriever dockerCredentialHelper(String credentialHelper) {
    return dockerCredentialHelper(Paths.get(credentialHelper), new DockerCredentialHelperFactory());
  }

  /**
   * Creates a new {@link CredentialRetriever} for retrieving credentials via a Docker credential
   * helper, such as {@code docker-credential-gcr}.
   *
   * @param credentialHelper the credential helper executable
   * @return a new {@link CredentialRetriever}
   * @see <a
   *     href="https://github.com/docker/docker-credential-helpers#development">https://github.com/docker/docker-credential-helpers#development</a>
   */
  public CredentialRetriever dockerCredentialHelper(Path credentialHelper) {
    return dockerCredentialHelper(credentialHelper, new DockerCredentialHelperFactory());
  }

  /**
   * Creates a new {@link CredentialRetriever} that tries common Docker credential helpers to
   * retrieve credentials based on the registry of the image, such as {@code docker-credential-gcr}
   * for images with the registry as {@code gcr.io}.
   *
   * @return a new {@link CredentialRetriever}
   */
  public CredentialRetriever inferCredentialHelper() {
    return inferCredentialHelper(new DockerCredentialHelperFactory());
  }

  /**
   * Creates a new {@link CredentialRetriever} that tries to retrieve credentials from Docker config
   * (located at {@code $USER_HOME/.docker/config.json}).
   *
   * @return a new {@link CredentialRetriever}
   * @see DockerConfigCredentialRetriever
   */
  public CredentialRetriever dockerConfig() {
    return dockerConfig(new DockerConfigCredentialRetriever(imageReference.getRegistry()));
  }

  /**
   * Creates a new {@link CredentialRetriever} that tries to retrieve credentials from a custom path
   * to a Docker config.
   *
   * @param dockerConfigFile the path to the Docker config file
   * @return a new {@link CredentialRetriever}
   * @see DockerConfigCredentialRetriever
   */
  public CredentialRetriever dockerConfig(Path dockerConfigFile) {
    return dockerConfig(
        new DockerConfigCredentialRetriever(imageReference.getRegistry(), dockerConfigFile));
  }

  @VisibleForTesting
  CredentialRetriever inferCredentialHelper(
      DockerCredentialHelperFactory dockerCredentialHelperFactory) {
    List<String> inferredCredentialHelperSuffixes = new ArrayList<>();
    for (String registrySuffix : COMMON_CREDENTIAL_HELPERS.keySet()) {
      if (!imageReference.getRegistry().endsWith(registrySuffix)) {
        continue;
      }
      String inferredCredentialHelperSuffix = COMMON_CREDENTIAL_HELPERS.get(registrySuffix);
      if (inferredCredentialHelperSuffix == null) {
        throw new IllegalStateException("No COMMON_CREDENTIAL_HELPERS should be null");
      }
      inferredCredentialHelperSuffixes.add(inferredCredentialHelperSuffix);
    }

    return () -> {
      for (String inferredCredentialHelperSuffix : inferredCredentialHelperSuffixes) {
        try {
          return retrieveFromDockerCredentialHelper(
              Paths.get(
                  DockerCredentialHelperFactory.CREDENTIAL_HELPER_PREFIX
                      + inferredCredentialHelperSuffix),
              dockerCredentialHelperFactory);

        } catch (DockerCredentialHelperNotFoundException ex) {
          if (ex.getMessage() != null) {
            // Warns the user that the specified (or inferred) credential helper is not on the
            // system.
            logger.warn(ex.getMessage());
            if (ex.getCause() != null && ex.getCause().getMessage() != null) {
              logger.info("  Caused by: " + ex.getCause().getMessage());
            }
          }

        } catch (NonexistentServerUrlDockerCredentialHelperException | IOException ex) {
          throw new CredentialRetrievalException(ex);
        }
      }
      return null;
    };
  }

  @VisibleForTesting
  CredentialRetriever dockerCredentialHelper(
      Path credentialHelper, DockerCredentialHelperFactory dockerCredentialHelperFactory) {
    return () -> {
      logger.info("Checking credentials from " + credentialHelper);

      try {
        return retrieveFromDockerCredentialHelper(credentialHelper, dockerCredentialHelperFactory);

      } catch (NonexistentServerUrlDockerCredentialHelperException ex) {
        logger.info(
            "No credentials for " + imageReference.getRegistry() + " in " + credentialHelper);
        return null;

      } catch (DockerCredentialHelperNotFoundException | IOException ex) {
        throw new CredentialRetrievalException(ex);
      }
    };
  }

  @VisibleForTesting
  CredentialRetriever dockerConfig(
      DockerConfigCredentialRetriever dockerConfigCredentialRetriever) {
    return () -> {
      try {
        Credential dockerConfigCredentials = dockerConfigCredentialRetriever.retrieve();
        if (dockerConfigCredentials != null) {
          logger.info("Using credentials from Docker config for " + imageReference.getRegistry());
          return dockerConfigCredentials;
        }

      } catch (IOException ex) {
        logger.info("Unable to parse Docker config");
      }
      return null;
    };
  }

  private Credential retrieveFromDockerCredentialHelper(
      Path credentialHelper, DockerCredentialHelperFactory dockerCredentialHelperFactory)
      throws NonexistentServerUrlDockerCredentialHelperException,
          DockerCredentialHelperNotFoundException, IOException {
    Credential credentials =
        dockerCredentialHelperFactory
            .newDockerCredentialHelper(imageReference.getRegistry(), credentialHelper)
            .retrieve();
    logGotCredentialsFrom(credentialHelper.getFileName().toString());
    return credentials;
  }

  private void logGotCredentialsFrom(String credentialSource) {
    logger.info("Using " + credentialSource + " for " + imageReference.getRegistry());
  }
}

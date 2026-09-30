/**
 * Copyright (C) 2026 Expedia, Inc.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.expediagroup.beekeeper.cleanup.aws;

import java.net.URI;
import java.time.Duration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.LegacyMd5Plugin;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Builds SDK v2 S3 clients configured to match the defaults of the SDK v1 {@code AmazonS3ClientBuilder}: v1 timeouts,
 * Content-MD5 on operations that require a checksum, and the v1 credential environment variable and system property
 * names ({@code AWS_ACCESS_KEY}, {@code AWS_SECRET_KEY}, {@code aws.secretKey}) ahead of the v2 default chain.
 */
public final class AwsS3ClientFactory {

  private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(50);

  private AwsS3ClientFactory() {}

  public static software.amazon.awssdk.services.s3.S3Client defaultClient() {
    return builder().build();
  }

  public static software.amazon.awssdk.services.s3.S3Client endpointClient(String endpoint, String region) {
    return builder()
        .endpointOverride(URI.create(endpoint.contains("://") ? endpoint : "https://" + endpoint))
        .region(Region.of(region))
        .build();
  }

  private static S3ClientBuilder builder() {
    return software.amazon.awssdk.services.s3.S3Client.builder()
        .httpClientBuilder(ApacheHttpClient.builder()
            .connectionTimeout(CONNECTION_TIMEOUT)
            .socketTimeout(SOCKET_TIMEOUT))
        .credentialsProvider(AwsCredentialsProviderChain.builder()
            .reuseLastProviderEnabled(true)
            .credentialsProviders(
                AwsS3ClientFactory::v1EnvironmentVariableCredentials,
                AwsS3ClientFactory::v1SystemPropertyCredentials,
                DefaultCredentialsProvider.builder().build())
            .build())
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .addPlugin(LegacyMd5Plugin.create());
  }

  private static AwsCredentials v1EnvironmentVariableCredentials() {
    return credentials("environment variables",
        firstNonEmpty(System.getenv("AWS_ACCESS_KEY_ID"), System.getenv("AWS_ACCESS_KEY")),
        firstNonEmpty(System.getenv("AWS_SECRET_KEY"), System.getenv("AWS_SECRET_ACCESS_KEY")),
        System.getenv("AWS_SESSION_TOKEN"));
  }

  private static AwsCredentials v1SystemPropertyCredentials() {
    return credentials("system properties", System.getProperty("aws.accessKeyId"),
        System.getProperty("aws.secretKey"), System.getProperty("aws.sessionToken"));
  }

  private static AwsCredentials credentials(String source, String accessKey, String secretKey, String sessionToken) {
    if (isEmpty(accessKey) || isEmpty(secretKey)) {
      throw SdkClientException.create("Unable to load AWS credentials from " + source);
    }
    if (isEmpty(sessionToken)) {
      return AwsBasicCredentials.create(accessKey.trim(), secretKey.trim());
    }
    return AwsSessionCredentials.create(accessKey.trim(), secretKey.trim(), sessionToken.trim());
  }

  private static String firstNonEmpty(String first, String second) {
    return isEmpty(first) ? second : first;
  }

  private static boolean isEmpty(String value) {
    return value == null || value.trim().isEmpty();
  }
}

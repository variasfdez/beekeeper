/**
 * Copyright (C) 2019-2025 Expedia, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.expediagroup.beekeeper.path.cleanup.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.URL;
import java.util.Collections;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.graphite.GraphiteMeterRegistry;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;

import com.expediagroup.beekeeper.cleanup.aws.S3Client;
import com.expediagroup.beekeeper.cleanup.aws.S3PathCleaner;
import com.expediagroup.beekeeper.cleanup.monitoring.BytesDeletedReporter;
import com.expediagroup.beekeeper.cleanup.path.PathCleaner;
import com.expediagroup.beekeeper.cleanup.service.CleanupService;
import com.expediagroup.beekeeper.cleanup.service.DisableTablesService;
import com.expediagroup.beekeeper.cleanup.service.RepositoryCleanupService;
import com.expediagroup.beekeeper.core.repository.BeekeeperHistoryRepository;
import com.expediagroup.beekeeper.core.repository.HousekeepingPathRepository;
import com.expediagroup.beekeeper.core.service.BeekeeperHistoryService;
import com.expediagroup.beekeeper.path.cleanup.service.PagingPathCleanupService;
import com.expediagroup.beekeeper.path.cleanup.service.PathRepositoryCleanupService;

@ExtendWith(MockitoExtension.class)
class CommonBeansTest {

  private static final String AWS_S3_ENDPOINT_PROPERTY = "aws.s3.endpoint";
  private static final String AWS_REGION_PROPERTY = "aws.region";
  private static final String REGION = "us-west-2";
  private static final String AWS_ENDPOINT = String.join(".", "s3", REGION, "amazonaws.com");
  private static final String ENDPOINT_HOST = "endpoint";
  // v2 endpointOverride requires an absolute URI, unlike v1 EndpointConfiguration
  private static final String ENDPOINT = "http://" + ENDPOINT_HOST;
  private static final String BUCKET = "bucket";
  private static final String KEY = "key";
  private static final String AWS_ACCESS_KEY_ID_PROPERTY = "aws.accessKeyId";
  private static final String AWS_SECRET_KEY_PROPERTY = "aws.secretKey";
  private static final String ACCESS_KEY = "access-key";
  private static final String SECRET_KEY = "secret-key";

  private final boolean dryRunEnabled = false;
  private final CommonBeans commonBeans = new CommonBeans();
  private @Mock HousekeepingPathRepository repository;
  private @Mock BytesDeletedReporter bytesDeletedReporter;
  private @Mock BeekeeperHistoryRepository beekeeperHistoryRepository;

  @BeforeEach
  void setUp() {
    System.setProperty(AWS_REGION_PROPERTY, REGION);
    System.setProperty(AWS_S3_ENDPOINT_PROPERTY, ENDPOINT);
  }

  @AfterAll
  static void tearDown() {
    System.clearProperty(AWS_REGION_PROPERTY);
    System.clearProperty(AWS_S3_ENDPOINT_PROPERTY);
  }

  @Test
  void typicalAmazonClient() {
    software.amazon.awssdk.services.s3.S3Client amazonS3 = commonBeans.amazonS3();
    URL url = getUrl(amazonS3);
    assertThat(url.getHost()).isEqualTo(String.join(".", BUCKET, AWS_ENDPOINT));
  }

  @Test
  void endpointConfiguredAmazonClient() {
    System.setProperty(AWS_S3_ENDPOINT_PROPERTY, ENDPOINT);
    software.amazon.awssdk.services.s3.S3Client amazonS3 = commonBeans.amazonS3Test();
    URL url = getUrl(amazonS3);
    // path-style access: the bucket is in the path rather than the host
    assertThat(url.getHost()).isEqualTo(ENDPOINT_HOST);
    assertThat(url.getPath()).isEqualTo("/" + BUCKET + "/" + KEY);
  }

  @Test
  void testClientReadsV1StyleCredentialSystemProperties() {
    System.setProperty(AWS_ACCESS_KEY_ID_PROPERTY, ACCESS_KEY);
    System.setProperty(AWS_SECRET_KEY_PROPERTY, SECRET_KEY);
    try {
      AwsCredentials credentials = CommonBeans.testCredentialsProvider().resolveCredentials();
      assertThat(credentials.accessKeyId()).isEqualTo(ACCESS_KEY);
      assertThat(credentials.secretAccessKey()).isEqualTo(SECRET_KEY);
    } finally {
      System.clearProperty(AWS_ACCESS_KEY_ID_PROPERTY);
      System.clearProperty(AWS_SECRET_KEY_PROPERTY);
    }
  }

  private URL getUrl(software.amazon.awssdk.services.s3.S3Client amazonS3) {
    return amazonS3.utilities().getUrl(GetUrlRequest.builder().bucket(BUCKET).key(KEY).build());
  }

  @Test
  void s3Client() {
    software.amazon.awssdk.services.s3.S3Client amazonS3 = commonBeans.amazonS3();
    S3Client s3Client = new S3Client(amazonS3, dryRunEnabled);
    S3Client beansS3Client = commonBeans.s3Client(amazonS3, dryRunEnabled);
    assertThat(s3Client).isEqualToComparingFieldByField(beansS3Client);
  }

  @Test
  void verifyS3pathCleaner() {
    S3Client s3Client = commonBeans.s3Client(commonBeans.amazonS3(), dryRunEnabled);
    MeterRegistry meterRegistry = mock(GraphiteMeterRegistry.class);

    PathCleaner pathCleaner = commonBeans.pathCleaner(s3Client, bytesDeletedReporter);
    assertThat(pathCleaner).isInstanceOf(S3PathCleaner.class);
  }

  @Test
  void cleanupService() {
    CleanupService cleanupService = commonBeans.cleanupService(Collections.emptyList(), 2, dryRunEnabled);
    assertThat(cleanupService).isInstanceOf(PagingPathCleanupService.class);
  }

  @Test
  public void repositoryCleanupService() {
    RepositoryCleanupService cleanupService = commonBeans.repositoryCleanupService(repository, 5);
    assertThat(cleanupService).isInstanceOf(PathRepositoryCleanupService.class);
  }

  @Test
  public void verifyDisableTablesService() {
    DisableTablesService disableTablesService = commonBeans.disableTablesService();
    assertThat(disableTablesService).isNotNull();
  }

  @Test
  public void verifyBeekeeperHistoryService(){
    BeekeeperHistoryService beekeeperHistoryService = commonBeans.beekeeperHistoryService(beekeeperHistoryRepository);
    assertThat(beekeeperHistoryService).isInstanceOf(BeekeeperHistoryService.class);
  }
}

/**
 * Copyright (C) 2019-2026 Expedia, Inc.
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Rule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.EncodingType;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Uses LocalStack 3.7.0 because 0.14.2 ignores encoding-type=url and returns control characters
 * unencoded, which is not valid XML. LocalStack 3.7.0 compares URL-encoded keys with the raw
 * continuation token, so the keys differ before their first encoded character to keep its pages
 * correct.
 */
@Testcontainers
class S3ClientUrlEncodingTest {

  private static final String BUCKET = "bucket";
  private static final int S3_PAGE_SIZE = 1000;

  @Rule
  public static LocalStackContainer awsContainer =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.7.0"))
          .withServices(S3);

  static {
    awsContainer.start();
  }

  private static software.amazon.awssdk.services.s3.S3Client amazonS3;

  @BeforeAll
  static void setUp() {
    amazonS3 =
        software.amazon.awssdk.services.s3.S3Client.builder()
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("accesskey", "secretkey")))
            .endpointOverride(URI.create(awsContainer.getEndpointOverride(S3).toString()))
            .region(Region.of(awsContainer.getRegion()))
            .forcePathStyle(true)
            .build();
    amazonS3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
  }

  @Test
  void listObjectsDecodesControlCharacterKeysAcrossPages() {
    String prefix = "table/partition_1";
    Set<String> expectedKeys = new HashSet<>();
    for (int i = 1; i <= S3_PAGE_SIZE + 1; i++) {
      String key = prefix + String.format("/file%04d\u0001hour=00%%3A00", i);
      amazonS3.putObject(
          PutObjectRequest.builder().bucket(BUCKET).key(key).build(), RequestBody.empty());
      expectedKeys.add(key);
    }
    ListObjectsV2Response firstPage =
        amazonS3.listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(BUCKET)
                .prefix(prefix)
                .encodingType(EncodingType.URL)
                .build());
    assertThat(firstPage.encodingType()).isEqualTo(EncodingType.URL);
    assertThat(firstPage.prefix()).isEqualTo(prefix);
    assertThat(firstPage.contents()).hasSize(S3_PAGE_SIZE);
    assertThat(firstPage.isTruncated()).isTrue();

    List<S3Object> result = new S3Client(amazonS3, false).listObjects(BUCKET, prefix);

    assertThat(result).extracting(S3Object::key).containsExactlyInAnyOrderElementsOf(expectedKeys);
  }
}

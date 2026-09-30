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

import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

import java.util.List;
import java.util.stream.Collectors;

import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

final class S3TestUtils {

  static final DockerImageName LOCALSTACK_IMAGE = DockerImageName.parse("localstack/localstack:3.7.0");
  private static final String REGION = "us-east-1";

  private S3TestUtils() {}

  static software.amazon.awssdk.services.s3.S3Client createS3Client(LocalStackContainer container) {
    return software.amazon.awssdk.services.s3.S3Client.builder()
        .endpointOverride(container.getEndpointOverride(S3))
        .region(Region.of(REGION))
        .forcePathStyle(true)
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("accesskey", "secretkey")))
        .build();
  }

  static void createEmptyBucket(software.amazon.awssdk.services.s3.S3Client s3, String bucket) {
    try {
      s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    } catch (BucketAlreadyOwnedByYouException e) {
      // bucket is reused across tests
    }
    List<ObjectIdentifier> objects;
    while (!(objects = listObjects(s3, bucket, "").stream()
        .map(object -> ObjectIdentifier.builder().key(object.key()).build())
        .collect(Collectors.toList())).isEmpty()) {
      s3.deleteObjects(DeleteObjectsRequest.builder()
          .bucket(bucket)
          .delete(Delete.builder().objects(objects).build())
          .build());
    }
  }

  static void putObject(software.amazon.awssdk.services.s3.S3Client s3, String bucket, String key, String content) {
    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromString(content));
  }

  static boolean doesObjectExist(software.amazon.awssdk.services.s3.S3Client s3, String bucket, String key) {
    try {
      s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    }
  }

  static boolean doesBucketExist(software.amazon.awssdk.services.s3.S3Client s3, String bucket) {
    try {
      s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
      return true;
    } catch (NoSuchBucketException e) {
      return false;
    }
  }

  /** Returns the first page (up to 1000 objects) under {@code prefix}. */
  static List<S3Object> listObjects(software.amazon.awssdk.services.s3.S3Client s3, String bucket, String prefix) {
    return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build()).contents();
  }
}

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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

/**
 * Asserts the AmazonS3URI behaviour which S3Uri replaces: keys are returned verbatim and the same
 * IllegalArgumentExceptions are thrown for invalid URIs.
 */
class S3UriTest {

  @Test
  void typical() {
    S3Uri uri = new S3Uri("s3://bucket/dir/file");
    assertThat(uri.getBucket()).isEqualTo("bucket");
    assertThat(uri.getKey()).isEqualTo("dir/file");
  }

  @Test
  void keyWithSpace() {
    S3Uri uri = new S3Uri("s3://bucket/dir/ /file");
    assertThat(uri.getBucket()).isEqualTo("bucket");
    assertThat(uri.getKey()).isEqualTo("dir/ /file");
  }

  @Test
  void encodedCharactersAreNotDecoded() {
    S3Uri uri = new S3Uri("s3://bucket/dir/file%20name+plus");
    assertThat(uri.getBucket()).isEqualTo("bucket");
    assertThat(uri.getKey()).isEqualTo("dir/file%20name+plus");
  }

  @Test
  void keyWithSentinelSuffix() {
    S3Uri uri = new S3Uri("s3://bucket/table/partition_1_$folder$");
    assertThat(uri.getBucket()).isEqualTo("bucket");
    assertThat(uri.getKey()).isEqualTo("table/partition_1_$folder$");
  }

  @Test
  void bucketOnlyHasNullKey() {
    S3Uri uri = new S3Uri("s3://bucket");
    assertThat(uri.getBucket()).isEqualTo("bucket");
    assertThat(uri.getKey()).isNull();
  }

  @Test
  void bucketWithTrailingSlashHasNullKey() {
    S3Uri uri = new S3Uri("s3://bucket/");
    assertThat(uri.getBucket()).isEqualTo("bucket");
    assertThat(uri.getKey()).isNull();
  }

  @Test
  void noScheme() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new S3Uri("bucket/key"))
        .withMessage("Invalid S3 URI: no hostname: bucket/key");
  }

  @Test
  void schemeIsNotS3() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new S3Uri("hdfs://bucket/key"))
        .withMessage(
            "Invalid S3 URI: hostname does not appear to be a valid S3 endpoint: hdfs://bucket/key");
  }

  @Test
  void noBucket() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new S3Uri("s3:///key"))
        .withMessage("Invalid S3 URI: no bucket: s3:///key");
  }
}

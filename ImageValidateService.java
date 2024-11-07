package com.sktelecom.tmap.flerken.profile.personalinfo.service;

import com.sktelecom.tmap.flerken.profile.personalinfo.controller.dto.ProfileImageSaveResponseDto;
import com.sktelecom.tmap.flerken.prop.ProfileImageProperties;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsRequest;
import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsResponse;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.S3Object;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;

@Slf4j
@RequiredArgsConstructor
@Service
public class ImageValidateService {

  private final ProfileImageProperties profileImageProperties;

  @Value("${aws.service-account.role-arn}")
  private String roleArn;

  private S3Client s3Client;

  public ProfileImageSaveResponseDto validateImage(String fileName) {
    String photo = fileNameWithUploadPath(fileName);
    DetectModerationLabelsRequest moderationLabelsRequest = buildModerationLabelsRequest(photo);
    RekognitionClient rekognitionClient = buildRekognitionClient();
    boolean validated = false;
    try {
      DetectModerationLabelsResponse response = rekognitionClient.detectModerationLabels(moderationLabelsRequest);
      log.debug("Detected labels for {}", photo);
      validated = filterByRejectWords(response); //이미지 검증 결과 유해한 이미지 필터
    } catch (Exception e) {
      log.error("이미지({}) 분석 중 오류 발생 : {}", photo, e.getMessage(), e);
    } finally {
      rekognitionClient.close();
    }

    return ProfileImageSaveResponseDto.builder()
        .validated(validated)
        .deployPath(validated ? fileNameWithDeployPath(fileName) : null)
        .build();
  }

  private String fileNameWithUploadPath(String fileName) {
    return new StringBuilder()
        .append(profileImageProperties.getS3().getUploadPath())
        .append(fileName)
        .toString();
  }

  private String fileNameWithDeployPath(String fileName) {
    return new StringBuilder()
        .append(profileImageProperties.getS3().getBasePath())
        .append(LocalDateTime.now().getMonth().getValue())
        .append("/")
        .append(fileName)
        .toString();
  }

  private RekognitionClient buildRekognitionClient() {
    return RekognitionClient.builder()
        .region(Region.AP_NORTHEAST_2)
        .credentialsProvider(WebIdentityTokenFileCredentialsProvider.builder()
            .roleArn(roleArn)
            .build())
        .build();
  }

  private RekognitionClient buildRekognitionClientForLocalSsoEnv() {
    //for local aws sso environment. need install aws-vault
    return RekognitionClient.builder()
        .region(Region.AP_NORTHEAST_2)
        .credentialsProvider(ProfileCredentialsProvider.create("app-tmap-service-dev"))
        .build();
  }

  private S3Client getS3Client() {
    if(s3Client == null) {
      s3Client = S3Client.builder()
          .region(Region.AP_NORTHEAST_2)
          .credentialsProvider(WebIdentityTokenFileCredentialsProvider.builder()
              .roleArn(roleArn)
              .build())
          .build();
    }
    return s3Client;
  }

  private S3Client getS3ClientForLocalSsoEnv() {
    //for local aws sso environment. need install aws-vault
    if(s3Client == null) {
      s3Client = S3Client.builder()
          .region(Region.AP_NORTHEAST_2)
          .credentialsProvider(ProfileCredentialsProvider.create("app-tmap-service-dev"))
          .build();
    }
    return s3Client;
  }

  /**
   * 이미지 분석 요청 객체 생성
   * @param photo
   * @return
   */
  private DetectModerationLabelsRequest buildModerationLabelsRequest(String photo) {
    return DetectModerationLabelsRequest.builder()
        .image(Image.builder().s3Object(
                S3Object.builder()
                    .name(photo)
                    .bucket(profileImageProperties.getS3().getBucketName())
                    .build())
            .build())
        .minConfidence(profileImageProperties.getPolicy().getMinConfidence())
        .build();
  }

  private boolean filterByRejectWords(DetectModerationLabelsResponse result) {
    log.debug("Content Types : {}", result.contentTypes());
    List<String> labelNames = result.moderationLabels().stream()
            .map(label -> label.name())
            .collect(Collectors.toList());
    log.debug("Labels : {}", labelNames);
    return profileImageProperties.getPolicy().getRejectWords().stream().noneMatch(labelNames::contains);
  }

  public void moveFileToDeployPath(String fileName) {
    String uploadFile = fileNameWithUploadPath(fileName);
    String deployFile = fileNameWithDeployPath(fileName);
    copyFile(profileImageProperties.getS3().getBucketName(), uploadFile,
        profileImageProperties.getS3().getBucketName(), deployFile);
    deleteFile(profileImageProperties.getS3().getBucketName(), uploadFile);

    log.debug("move file to deploy path : {}", deployFile);
  }

  public void moveFileToInappropriatePath(String fileName) {
    String uploadFile = fileNameWithUploadPath(fileName);
    copyFile(profileImageProperties.getS3().getBucketName(), uploadFile,
        profileImageProperties.getS3().getInappropriateBucketName(), fileName);
    deleteFile(profileImageProperties.getS3().getBucketName(), uploadFile);

    log.debug("move file to inappropriate path : {}", fileName);
  }

  private void copyFile(String sourceBucket, String sourceFileName, String destinationBucket,
      String destinationFileName) {
    getS3Client().copyObject(CopyObjectRequest.builder()
        .sourceBucket(sourceBucket)
        .sourceKey(sourceFileName)
        .destinationBucket(destinationBucket)
        .destinationKey(destinationFileName)
        .build());
  }

  private void deleteFile(String bucketName, String fileName) {
    DeleteObjectResponse response = getS3Client().deleteObject(DeleteObjectRequest.builder()
        .bucket(bucketName)
        .key(fileName)
        .build());

    log.info("delete result : {}", response);
  }

  /**
   * <PRE>
   * URL에 해당한느 파일 삭제
   * CDN 버킷에 있는 파일을 삭제한다.
   * </PRE>
   * @param imageUrl
   */
  public void deleteFileByUrl(String imageUrl) {
    if(StringUtils.isEmpty(imageUrl)) {
      return;
    }
    String host = profileImageProperties.getCdn().getHost();
    if(StringUtils.isEmpty(host)) {
      return;
    }
    String bucketName = profileImageProperties.getS3().getBucketName();
    String fileName = imageUrl.substring(host.length());
    deleteFile(bucketName, fileName);
    log.debug("deleted : {}, {}", bucketName, fileName);
  }
}

package com.urchin.release.mgt.service;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.urchin.release.mgt.config.properties.BinaryProperties;
import com.urchin.release.mgt.exception.BinaryVersionMissingException;
import com.urchin.release.mgt.model.Binary;
import com.urchin.release.mgt.model.BinaryType;
import com.urchin.release.mgt.model.audit.BinaryDownloadAudit;
import com.urchin.release.mgt.model.audit.BinaryVersionAudit;
import com.urchin.release.mgt.repository.BinaryDownloadAuditRepository;
import com.urchin.release.mgt.repository.BinaryVersionAuditRepository;
import com.urchin.release.mgt.repository.DownloadByVersionCount;
import com.urchin.release.mgt.utils.AppVersionUtils;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class BinaryService {

    private BinaryProperties binaryProperties;
    private BinaryDownloadAuditRepository binaryDownloadAuditRepository;
    private BinaryVersionAuditRepository binaryVersionAuditRepository;

    @Autowired
    public BinaryService(BinaryProperties binaryProperties, BinaryDownloadAuditRepository binaryDownloadAuditRepository,
                         BinaryVersionAuditRepository binaryVersionAuditRepository) {
        this.binaryProperties = binaryProperties;
        this.binaryDownloadAuditRepository = binaryDownloadAuditRepository;
        this.binaryVersionAuditRepository = binaryVersionAuditRepository;
    }

    public List<Binary> getLastBinaries(){
        List<Binary> lastBinaries = new ArrayList<>();
        for(BinaryType binaryType : BinaryType.values()) {
            getLastBinary(binaryType).ifPresent(lastBinaries::add);
        }

        return lastBinaries;
    }

    public Optional<Binary> getLastBinary(BinaryType binaryType){
        return streamBinaries()
                .filter(b -> b.getFileName().endsWith(binaryType.getExtension()))
                .max((e1, e2) -> AppVersionUtils.compareVersion(e1.getVersion(), e2.getVersion()));
    }

    public void uploadOrReplace(String filename, byte[] bytes) {
        if(!hasVersion(filename)){
            throw new BinaryVersionMissingException("Binary version is missing in filename: " + filename);
        }

        deleteIfExist(filename);
        upload(filename, bytes);
    }

    public void newAuditDownload(String appVersion, BinaryType binaryType){
        binaryDownloadAuditRepository.saveAndFlush(new BinaryDownloadAudit(appVersion, binaryType));
    }

    public void newAuditVersion(String appVersion, BinaryType binaryType){
        binaryVersionAuditRepository.saveAndFlush(new BinaryVersionAudit(appVersion, binaryType));
    }

    public Map<LocalDate, Long> findBinaryVersionAuditsGroupByDate(LocalDate startDate, LocalDate endDate){
        List<BinaryVersionAudit> binaryVersionAudits = binaryVersionAuditRepository.findByDateTimeBetween(toStartDateTime(startDate), toEndDateTime(endDate));
        return binaryVersionAudits.stream().collect(Collectors.groupingBy(bva -> bva.getDateTime().toLocalDate(), Collectors.counting()));
    }

    public Map<LocalDate, Long> findBinaryDownloadAuditsGroupByDate(BinaryType binaryType, LocalDate startDate, LocalDate endDate){
        List<BinaryDownloadAudit> binaryDownloadAudits = binaryDownloadAuditRepository.findByBinaryTypeAndDateTimeBetween(binaryType, toStartDateTime(startDate), toEndDateTime(endDate));
        return binaryDownloadAudits.stream().collect(Collectors.groupingBy(bda -> bda.getDateTime().toLocalDate(), Collectors.counting()));
    }

    public List<DownloadByVersionCount> findDownloadsByVersionCount(){
        return binaryDownloadAuditRepository.findDownloadsByVersionCount();
    }

    private LocalDateTime toStartDateTime(LocalDate startDate){
        return startDate.atTime(LocalTime.MIN);
    }

    private LocalDateTime toEndDateTime(LocalDate endDate){
        return endDate.atTime(LocalTime.MAX);
    }

    private Stream<Binary> streamBinaries(){
        final AmazonS3 s3Authenticated = buildAwsS3Authenticated();
        ListObjectsV2Result result = s3Authenticated.listObjectsV2(binaryProperties.getAwsBucketName());
        String bucketUrl = binaryProperties.getBaseUrl() + binaryProperties.getAwsBucketName() + "/";
        Stream<Binary> binariesStream = result.getObjectSummaries()
                .stream()
                .map(o -> new Binary(bucketUrl + o.getKey(), o.getSize(), retrieveVersion(o.getKey()), toLocalDateTime(o.getLastModified())));
        s3Authenticated.shutdown();
        return binariesStream;
    }

    public void deleteIfExist(String filename){
        final AmazonS3 s3Authenticated = buildAwsS3Authenticated();
        s3Authenticated.deleteObject(binaryProperties.getAwsBucketName(), filename);
        s3Authenticated.shutdown();
    }

    private void upload(String filename, byte[] bytes){
        final AmazonS3 s3Authenticated = buildAwsS3Authenticated();
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentLength(bytes.length);
        objectMetadata.setLastModified(new Date());
        s3Authenticated.putObject(binaryProperties.getAwsBucketName(), filename, new ByteArrayInputStream(bytes), objectMetadata);

        makePublicReadable(s3Authenticated, filename);
        s3Authenticated.shutdown();
    }

    private void makePublicReadable(final AmazonS3 s3Authenticated, String filename){
        AccessControlList acl = s3Authenticated.getObjectAcl(binaryProperties.getAwsBucketName(), filename);
        Grantee grantee = GroupGrantee.AllUsers;
        Permission permission = Permission.Read;
        acl.grantPermission(grantee, permission);
        s3Authenticated.setObjectAcl(binaryProperties.getAwsBucketName(), filename, acl);
    }

    private boolean hasVersion(String filename){
        return Pattern.matches(binaryProperties.getVersionPattern(), filename);
    }

    private String retrieveVersion(String filename){
        Matcher matcher = Pattern.compile(binaryProperties.getVersionPattern()).matcher(filename);
        if(!matcher.find()){
            throw new IllegalStateException("Impossible to find binary version on '" + filename + "' with: " + binaryProperties.getVersionPattern());
        }

        return matcher.group(1);
    }

    public LocalDateTime toLocalDateTime(Date dateToConvert) {
        return dateToConvert.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    private AmazonS3 buildAwsS3Authenticated(){
        //Pre-requisites:
        //  - In AWS IAM:
        //      - Create users "green-city" with programmatic access
        //      - Create group "green-city-group" with policy "AmazonS3FullAccess"
        //      - Add user to group
        //      - Download credential (access key ID & secret access key)
        //  - In /src/main/resources/application-awssecurity.yml:
        //      - Define properties binary.aws-access-key-id & binary.aws-secret-access-key

        if(Strings.isBlank(binaryProperties.getAwsAccessKeyId())){
            throw new IllegalArgumentException("AWS access key ID properties must be provided");
        }
        if(Strings.isBlank(binaryProperties.getAwsSecretAccessKey())){
            throw new IllegalArgumentException("AWS secret access key properties must be provided");
        }

        return AmazonS3ClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(binaryProperties.getAwsAccessKeyId(), binaryProperties.getAwsSecretAccessKey())))
                .withRegion(Regions.EU_WEST_3)
                .build();
    }
}

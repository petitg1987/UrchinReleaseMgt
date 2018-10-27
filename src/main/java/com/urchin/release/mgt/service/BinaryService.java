package com.urchin.release.mgt.service;

import com.urchin.release.mgt.config.properties.BinaryProperties;
import com.urchin.release.mgt.model.audit.BinaryDownloadAudit;
import com.urchin.release.mgt.model.BinaryType;
import com.urchin.release.mgt.model.audit.BinaryVersionAudit;
import com.urchin.release.mgt.repository.BinaryDownloadAuditRepository;
import com.urchin.release.mgt.repository.BinaryVersionAuditRepository;
import com.urchin.release.mgt.repository.DownloadByVersionCount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

    public List<Path> getBinaryPaths(){
        List<Path> binaryPaths = new ArrayList<>(BinaryType.values().length);
        Arrays.stream(BinaryType.values()).forEach(bt -> binaryPaths.add(getBinaryPath(bt)));
        return binaryPaths;
    }

    public String getBinaryVersion(BinaryType binaryType){
        Path binaryPath = getBinaryPath(binaryType);

        Matcher matcher = Pattern.compile(binaryProperties.getVersionPattern()).matcher(binaryPath.getFileName().toString());
        if(!matcher.find()){
            throw new IllegalArgumentException("Impossible to find binary version on '" + binaryPath.getFileName().toString() + "' with: " + binaryProperties.getVersionPattern());
        }

        return matcher.group(0);
    }

    public InputStream getBinaryStream(BinaryType binaryType){
        Path binaryPath = getBinaryPath(binaryType);

        try {
            return Files.newInputStream(binaryPath);
        } catch (IOException e) {
            throw new IllegalArgumentException("Impossible to create stream from path: " + binaryPath.toAbsolutePath().toString(), e);
        }
    }

    public InputStream getBinaryStream(String filename){
        for(BinaryType binaryType : BinaryType.values()){
            Path binaryPath = getBinaryPath(binaryType);

            if(binaryPath.getFileName().toString().equals(filename)){
                return getBinaryStream(binaryType);
            }
        }

        throw new IllegalArgumentException("Fail to find file with filename: " + filename);
    }

    public void newAuditDownload(String appVersion, BinaryType binaryType){
        binaryDownloadAuditRepository.saveAndFlush(new BinaryDownloadAudit(appVersion, binaryType));
    }

    public void newAuditVersion(String appVersion, BinaryType binaryType){
        binaryVersionAuditRepository.saveAndFlush(new BinaryVersionAudit(appVersion, binaryType));
    }

    public Map<LocalDate, Long> findBinaryVersionAuditsGroupByDate(LocalDate startDate, LocalDate endDate){
        LocalDateTime startDateTime = startDate.atTime(LocalTime.MIN);
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);

        List<BinaryVersionAudit> binaryVersionAudits = binaryVersionAuditRepository.findByDateTimeBetween(startDateTime, endDateTime);
        return binaryVersionAudits.stream().collect(Collectors.groupingBy(bva -> bva.getDateTime().toLocalDate(), Collectors.counting()));
    }

    public Map<LocalDate, Long> findBinaryDownloadAuditsGroupByDate(BinaryType binaryType, LocalDate startDate, LocalDate endDate){
        LocalDateTime startDateTime = startDate.atTime(LocalTime.MIN);
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);

        List<BinaryDownloadAudit> binaryDownloadAudits = binaryDownloadAuditRepository.findByBinaryTypeAndDateTimeBetween(binaryType, startDateTime, endDateTime);
        return binaryDownloadAudits.stream().collect(Collectors.groupingBy(bda -> bda.getDateTime().toLocalDate(), Collectors.counting()));
    }

    public List<DownloadByVersionCount> findDownloadsByVersionCount(){
        return binaryDownloadAuditRepository.findDownloadsByVersionCount();
    }

    private Path getBinaryPath(BinaryType binaryType){
        return streamPathBinaries()
                .filter(p -> p.getFileName().toString().endsWith(binaryType.getExtension()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Impossible to find binary for extension: " + binaryType.getExtension()));
    }

    private Stream<Path> streamPathBinaries(){
        try {
            return Files.list(Paths.get(binaryProperties.getBaseFolder()))
                    .filter(Files::isRegularFile);
        } catch (IOException e) {
            throw new IllegalArgumentException("Impossible to read files in folder: " + binaryProperties.getBaseFolder(), e);
        }
    }
}

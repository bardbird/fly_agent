package com.fly.agent.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fly.agent.dao.entity.swe.SweRepoBlacklistEntity;
import com.fly.agent.dao.mapper.swe.SweRepoBlacklistMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * Maintains the repo-level blacklist used by SWE discovery jobs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SweRepoBlacklistService {

    public static final String DEFAULT_BLACKLIST_PATH = "~/Downloads/swe_existing_dataset_blacklist.xlsx";

    private static final int IMPORT_BATCH_SIZE = 100;

    private final SweRepoBlacklistMapper blacklistMapper;

    @PostConstruct
    public void initializeSchema() {
        // Schema is managed by Flyway migration V6__swe_repo_precheck_tables.sql.
    }

    public BlacklistImportResult importFromExcel(String rawPath) {
        initializeSchema();
        Path path = expandPath(StringUtils.hasText(rawPath) ? rawPath : DEFAULT_BLACKLIST_PATH);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Blacklist Excel file does not exist: " + path);
        }

        BlacklistImportResult result = new BlacklistImportResult();
        result.setPath(path.toString());
        result.setSheetName("Repo Blacklist");

        try (OPCPackage packageFile = OPCPackage.open(path.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(packageFile);
            Map<Integer, String> sharedStrings = new HashMap<>();
            if (hasSharedStrings(packageFile)) {
                Set<Integer> sharedStringIndexes = collectSharedStringIndexes(reader, result.getSheetName());
                sharedStrings = readSharedStrings(reader, sharedStringIndexes);
            }
            parseBlacklistSheet(reader, path, result, sharedStrings);
            log.info("Imported SWE repo blacklist, path={}, importedRows={}", path, result.getImportedRows());
            return result;
        } catch (IOException | OpenXML4JException | SAXException | ParserConfigurationException e) {
            throw new IllegalStateException("Failed to import blacklist Excel: " + path, e);
        }
    }

    private boolean hasSharedStrings(OPCPackage packageFile) {
        try {
            return packageFile.containPart(PackagingURIHelper.createPartName("/xl/sharedStrings.xml"));
        } catch (InvalidFormatException e) {
            return false;
        }
    }

    public boolean isBlacklisted(String repo) {
        String normalizedRepo = normalizeRepo(repo);
        if (!StringUtils.hasText(normalizedRepo)) {
            return false;
        }
        Long count = blacklistMapper.selectCount(new LambdaQueryWrapper<SweRepoBlacklistEntity>()
                .eq(SweRepoBlacklistEntity::getRepo, normalizedRepo));
        return count != null && count > 0;
    }

    public static String normalizeRepo(String repo) {
        if (!StringUtils.hasText(repo)) {
            return null;
        }
        String normalized = repo.trim();
        if (normalized.startsWith("http://github.com/")) {
            normalized = normalized.substring("http://github.com/".length());
        } else if (normalized.startsWith("https://github.com/")) {
            normalized = normalized.substring("https://github.com/".length());
        } else if (normalized.startsWith("github.com/")) {
            normalized = normalized.substring("github.com/".length());
        }
        int queryIndex = firstPositiveIndex(normalized.indexOf('?'), normalized.indexOf('#'));
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String[] parts = normalized.split("/");
        if (parts.length < 2) {
            return null;
        }
        normalized = parts[0] + "/" + parts[1];
        if (!normalized.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            return null;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static int firstPositiveIndex(int left, int right) {
        if (left < 0) {
            return right;
        }
        if (right < 0) {
            return left;
        }
        return Math.min(left, right);
    }

    private Set<Integer> collectSharedStringIndexes(XSSFReader reader, String sheetName)
            throws IOException, OpenXML4JException, ParserConfigurationException, SAXException {
        Set<Integer> indexes = new HashSet<>();
        try (InputStream sheetStream = openSheet(reader, sheetName)) {
            parseXml(sheetStream, new SharedStringIndexCollector(indexes));
        }
        return indexes;
    }

    private Map<Integer, String> readSharedStrings(XSSFReader reader, Set<Integer> neededIndexes)
            throws IOException, OpenXML4JException, ParserConfigurationException, SAXException {
        Map<Integer, String> sharedStrings = new HashMap<>();
        if (neededIndexes.isEmpty()) {
            return sharedStrings;
        }
        try (InputStream sharedStringsStream = reader.getSharedStringsData()) {
            parseXml(sharedStringsStream, new SelectiveSharedStringsHandler(neededIndexes, sharedStrings));
        }
        return sharedStrings;
    }

    private void parseBlacklistSheet(
            XSSFReader reader,
            Path path,
            BlacklistImportResult result,
            Map<Integer, String> sharedStrings)
            throws ParserConfigurationException, SAXException, IOException, OpenXML4JException {
        try (InputStream sheetStream = openSheet(reader, result.getSheetName())) {
            parseXml(sheetStream, new BlacklistSheetHandler(path, result, sharedStrings));
        }
    }

    private InputStream openSheet(XSSFReader reader, String sheetName) throws IOException, OpenXML4JException {
        XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
        while (sheets.hasNext()) {
            InputStream sheetStream = sheets.next();
            if (sheetName.equals(sheets.getSheetName())) {
                return sheetStream;
            }
            sheetStream.close();
        }
        throw new IllegalArgumentException("Sheet not found: " + sheetName);
    }

    private void parseXml(InputStream inputStream, DefaultHandler handler)
            throws ParserConfigurationException, SAXException, IOException {
        SAXParserFactory parserFactory = SAXParserFactory.newInstance();
        parserFactory.setNamespaceAware(true);
        XMLReader parser = parserFactory.newSAXParser().getXMLReader();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(inputStream));
    }

    private SweRepoBlacklistEntity rowEntity(
            Path path,
            String sheetName,
            Map<Integer, String> row,
            Map<String, Integer> headerIndexes,
            String repo) {
        SweRepoBlacklistEntity entity = new SweRepoBlacklistEntity();
        entity.setRepo(repo);
        entity.setGithubUrl(stringValue(row, headerIndexes, "github_url"));
        entity.setGithubStars(integerValue(row, headerIndexes, "github_stars"));
        entity.setBenchmarks(stringValue(row, headerIndexes, "benchmarks"));
        entity.setDatasets(stringValue(row, headerIndexes, "datasets"));
        entity.setSplits(stringValue(row, headerIndexes, "splits"));
        entity.setInstanceCount(integerValue(row, headerIndexes, "instance_count"));
        entity.setLanguages(stringValue(row, headerIndexes, "languages"));
        entity.setExampleInstanceId(stringValue(row, headerIndexes, "example_instance_id"));
        entity.setExampleBaseCommit(stringValue(row, headerIndexes, "example_base_commit"));
        entity.setSourceFile(path.toString());
        entity.setSourceSheet(sheetName);
        return entity;
    }

    private Map<String, Integer> headerIndexes(Map<Integer, String> header) {
        if (header == null) {
            throw new IllegalArgumentException("Repo Blacklist sheet header is empty");
        }
        Map<String, Integer> indexes = new HashMap<>();
        for (Map.Entry<Integer, String> entry : header.entrySet()) {
            String name = entry.getValue();
            if (StringUtils.hasText(name)) {
                indexes.put(name.trim().toLowerCase(Locale.ROOT), entry.getKey());
            }
        }
        return indexes;
    }

    private String stringValue(Map<Integer, String> row, Map<String, Integer> headerIndexes, String header) {
        Integer index = headerIndexes.get(header);
        if (index == null) {
            return null;
        }
        String value = row.get(index);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Integer integerValue(Map<Integer, String> row, Map<String, Integer> headerIndexes, String header) {
        String value = stringValue(row, headerIndexes, header);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            String normalized = value.replace(",", "").trim();
            int dotIndex = normalized.indexOf('.');
            if (dotIndex >= 0) {
                normalized = normalized.substring(0, dotIndex);
            }
            return Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Path expandPath(String rawPath) {
        String value = rawPath.trim();
        if (value.equals("~")) {
            return Paths.get(System.getProperty("user.home"));
        }
        if (value.startsWith("~/")) {
            return Paths.get(System.getProperty("user.home"), value.substring(2));
        }
        return Paths.get(value);
    }

    @Data
    public static class BlacklistImportResult {

        private String path;

        private String sheetName;

        private int readRows;

        private int importedRows;

        private int skippedRows;
    }

    private class BlacklistSheetHandler extends DefaultHandler {

        private final Path path;

        private final BlacklistImportResult result;

        private final Map<Integer, String> sharedStrings;

        private final List<SweRepoBlacklistEntity> batch = new ArrayList<>(IMPORT_BATCH_SIZE);

        private Map<String, Integer> headerIndexes;

        private Map<Integer, String> rowValues;

        private String cellType;

        private int cellIndex;

        private int lastCellIndex = -1;

        private boolean readingValue;

        private final StringBuilder value = new StringBuilder();

        private String resolvedCellValue;

        private BlacklistSheetHandler(Path path, BlacklistImportResult result, Map<Integer, String> sharedStrings) {
            this.path = path;
            this.result = result;
            this.sharedStrings = sharedStrings;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            String name = elementName(localName, qName);
            if ("row".equals(name)) {
                rowValues = new HashMap<>();
                lastCellIndex = -1;
                return;
            }
            if ("c".equals(name)) {
                cellType = attributes.getValue("t");
                cellIndex = cellIndex(attributes.getValue("r"), lastCellIndex + 1);
                value.setLength(0);
                resolvedCellValue = null;
                return;
            }
            if ("v".equals(name) || ("t".equals(name) && "inlineStr".equals(cellType))) {
                readingValue = true;
                value.setLength(0);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (readingValue) {
                value.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            String name = elementName(localName, qName);
            if ("v".equals(name)) {
                resolvedCellValue = resolveCellValue(value.toString(), cellType);
                readingValue = false;
                return;
            }
            if ("t".equals(name) && "inlineStr".equals(cellType)) {
                resolvedCellValue = appendInlineString(resolvedCellValue, value.toString());
                readingValue = false;
                return;
            }
            if ("c".equals(name)) {
                if (StringUtils.hasText(resolvedCellValue)) {
                    rowValues.put(cellIndex, resolvedCellValue);
                }
                lastCellIndex = cellIndex;
                return;
            }
            if ("row".equals(name)) {
                processRow(rowValues);
                rowValues = null;
            }
        }

        @Override
        public void endDocument() {
            flushBatch();
        }

        private void processRow(Map<Integer, String> row) {
            if (row == null || row.isEmpty()) {
                return;
            }
            if (headerIndexes == null) {
                headerIndexes = headerIndexes(row);
                if (!headerIndexes.containsKey("repo")) {
                    throw new IllegalArgumentException("Column not found in Repo Blacklist sheet: repo");
                }
                return;
            }

            result.setReadRows(result.getReadRows() + 1);
            String repo = normalizeRepo(row.get(headerIndexes.get("repo")));
            if (!StringUtils.hasText(repo)) {
                result.setSkippedRows(result.getSkippedRows() + 1);
                return;
            }

            batch.add(rowEntity(path, result.getSheetName(), row, headerIndexes, repo));
            result.setImportedRows(result.getImportedRows() + 1);
            if (batch.size() >= IMPORT_BATCH_SIZE) {
                flushBatch();
                log.info("Importing SWE repo blacklist, importedRows={}", result.getImportedRows());
            }
        }

        private void flushBatch() {
            if (batch.isEmpty()) {
                return;
            }
            batch.forEach(blacklistMapper::upsert);
            batch.clear();
        }

        private String resolveCellValue(String rawValue, String type) {
            if (!StringUtils.hasText(rawValue)) {
                return null;
            }
            if ("s".equals(type)) {
                try {
                    return sharedStrings.get(Integer.parseInt(rawValue.trim()));
                } catch (RuntimeException e) {
                    return null;
                }
            }
            if ("b".equals(type)) {
                return "1".equals(rawValue.trim()) ? "TRUE" : "FALSE";
            }
            return rawValue;
        }

        private String appendInlineString(String existing, String incoming) {
            if (!StringUtils.hasText(existing)) {
                return incoming;
            }
            return existing + incoming;
        }

        private String elementName(String localName, String qName) {
            return StringUtils.hasText(localName) ? localName : qName;
        }

        private int cellIndex(String cellReference, int fallback) {
            if (!StringUtils.hasText(cellReference)) {
                return fallback;
            }
            int index = 0;
            for (int i = 0; i < cellReference.length(); i++) {
                char value = cellReference.charAt(i);
                if (!Character.isLetter(value)) {
                    break;
                }
                index = index * 26 + Character.toUpperCase(value) - 'A' + 1;
            }
            return index - 1;
        }
    }

    private static class SharedStringIndexCollector extends DefaultHandler {

        private final Set<Integer> indexes;

        private String cellType;

        private boolean readingValue;

        private final StringBuilder value = new StringBuilder();

        private SharedStringIndexCollector(Set<Integer> indexes) {
            this.indexes = indexes;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            String name = elementName(localName, qName);
            if ("c".equals(name)) {
                cellType = attributes.getValue("t");
                return;
            }
            if ("v".equals(name) && "s".equals(cellType)) {
                readingValue = true;
                value.setLength(0);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (readingValue) {
                value.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = elementName(localName, qName);
            if ("v".equals(name) && readingValue) {
                try {
                    indexes.add(Integer.parseInt(value.toString().trim()));
                } catch (NumberFormatException ignored) {
                    // Ignore malformed shared string indexes.
                }
                readingValue = false;
                return;
            }
            if ("c".equals(name)) {
                cellType = null;
            }
        }
    }

    private static class SelectiveSharedStringsHandler extends DefaultHandler {

        private final Set<Integer> neededIndexes;

        private final Map<Integer, String> sharedStrings;

        private int sharedStringIndex = -1;

        private boolean needed;

        private boolean readingText;

        private final StringBuilder value = new StringBuilder();

        private SelectiveSharedStringsHandler(Set<Integer> neededIndexes, Map<Integer, String> sharedStrings) {
            this.neededIndexes = neededIndexes;
            this.sharedStrings = sharedStrings;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            String name = elementName(localName, qName);
            if ("si".equals(name)) {
                sharedStringIndex++;
                needed = neededIndexes.contains(sharedStringIndex);
                if (needed) {
                    value.setLength(0);
                }
                return;
            }
            if ("t".equals(name) && needed) {
                readingText = true;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (readingText) {
                value.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = elementName(localName, qName);
            if ("t".equals(name)) {
                readingText = false;
                return;
            }
            if ("si".equals(name) && needed) {
                sharedStrings.put(sharedStringIndex, value.toString());
                needed = false;
            }
        }
    }

    private static String elementName(String localName, String qName) {
        return StringUtils.hasText(localName) ? localName : qName;
    }
}

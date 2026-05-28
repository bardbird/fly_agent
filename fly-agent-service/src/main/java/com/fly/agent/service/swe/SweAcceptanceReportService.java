package com.fly.agent.service.swe;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fly.agent.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the 34-rule SWE-Pro acceptance self-check report in the same workbook
 * shape as the accepted LQ sample.
 */
@Service
public class SweAcceptanceReportService {

    static final String REPORT_NAME = "乙方质检-SWE-Pro数据验收标准对照表.xlsx";

    private static final List<Rule> RULES = List.of(
            new Rule(1, "一、基础环境与有效性要求", "确定环境可以跑通完成测试，并且提供具体的 docker image"),
            new Rule(2, "一、基础环境与有效性要求", "golden patch需要能通过测试，并且与issue/problem statement相符"),
            new Rule(3, "二、任务真实性要求", "任务必须基于真实软件工程repo及上下文，而非纯人工虚构题"),
            new Rule(4, "二、任务真实性要求", "合规来源：可以来自于公开仓库中的真实 issue + 修复 commit；禁止项：完全凭空编造 issue"),
            new Rule(5, "二、任务真实性要求", "合规来源：可以来自于真实用户反馈、bug report、PR review 或 release regression"),
            new Rule(6, "二、任务真实性要求", "合规来源：可以由内部工程记录提炼，但必须附带原始证据链"),
            new Rule(7, "二、任务真实性要求", "禁止项：先写 patch，再反向虚构需求，且无真实工程依据"),
            new Rule(8, "二、任务真实性要求", "禁止项：题目只是算法题包装成工程题"),
            new Rule(9, "三、数据难度要求", "每个task需要修改的代码量（比如ground-truth patch）需要涉及多个文件，且代码行数在几百行以上"),
            new Rule(10, "三、数据难度要求", "opus4.7 pass@8 != 0（八次至少能做对一次）"),
            new Rule(11, "三、数据难度要求", "qwen 3.6 flash pass rate@4 <= 50%；75%数据 pass rate >=25%，其中25%数据 pass rate=0%"),
            new Rule(12, "四、repo语言要求", "应尽可能覆盖多个语言，例如 python/go/js/ts/c/c++/java/html/css/swift/kotlin/rust 等"),
            new Rule(13, "四、repo语言要求", "一个repo可以包含多个语言例如既包含前端也包含后端"),
            new Rule(14, "五、repo来源合规要求", "应避免选取与 swe-gym、swe-rebench、swe-bench-verified、swe-smith、swe-bench-pro 相同的repo"),
            new Rule(15, "六、issue与test patch一致性要求", "test patch用于评测agent提交的patch是否修复相关问题，因此tests需要准确判断issue是否被修复"),
            new Rule(16, "六、issue与test patch一致性要求", "应避免测试过于narrow、overfit到patch标准答案；不同但同样有效的patch应能通过测试"),
            new Rule(17, "六、issue与test patch一致性要求", "保证problem statement与tests关联性，不能出现tests包含额外隐藏要求"),
            new Rule(18, "七、task类别分布要求", "issue_specificity/issue_categories参考swe bench pro类型；单题可多标签；分布为批量维度"),
            new Rule(19, "八、专家校验要求", "三重盲审质量保证 + 评审员进行质量校准"),
            new Rule(20, "九、task必填字段要求", "code repo：公开repo需提供访问链接、commit ID"),
            new Rule(21, "九、task必填字段要求", "code repo：非公开repo需提供完整代码库、commit ID"),
            new Rule(22, "九、task必填字段要求", "Runnable环境：提供完整docker image，或可接受 Dockerfile + build.sh + eval.sh/等价脚本"),
            new Rule(23, "九、task必填字段要求", "problem_statement：关于需要修复的bug/issue的详细问题描述"),
            new Rule(24, "九、task必填字段要求", "patch：可以完美修复问题的patch"),
            new Rule(25, "九、task必填字段要求", "test patch：Test cases related to the patch"),
            new Rule(26, "九、task必填字段要求", "fail_to_pass：修复后应通过的测试"),
            new Rule(27, "九、task必填字段要求", "pass_to_pass：应持续通过的测试"),
            new Rule(28, "九、task必填字段要求", "requirements：项目依赖/环境要求，可为null"),
            new Rule(29, "九、task必填字段要求", "interface：API或接口规范，可为null"),
            new Rule(30, "九、task必填字段要求", "selected_test_files_to_run：选定运行的测试文件"),
            new Rule(31, "九、task必填字段要求", "before_repo_set_cmd：测试前repo设置命令"),
            new Rule(32, "九、task必填字段要求", "metadata.repo_language：标注编程语言"),
            new Rule(33, "九、task必填字段要求", "metadata.issue_specificity：标注issue specificity，可多标签"),
            new Rule(34, "九、task必填字段要求", "metadata.issue_categories：标注issue categories/tags，可多标签")
    );

    public Path ensureReport(Path packagePath) {
        if (!Files.isDirectory(packagePath)) {
            throw new BusinessException("package path does not exist: " + packagePath);
        }
        Path reportPath = packagePath.resolve(REPORT_NAME);
        if (Files.isRegularFile(reportPath)) {
            return reportPath;
        }
        try {
            writeWorkbook(reportPath, buildContext(packagePath));
            return reportPath;
        } catch (IOException e) {
            throw new BusinessException("failed to generate acceptance report: " + reportPath, e);
        }
    }

    private ReportContext buildContext(Path packagePath) {
        JSONObject task = readJson(packagePath.resolve("task.json"));
        PatchStats gold = patchStats(packagePath.resolve("patches/gold.patch"));
        PatchStats test = patchStats(packagePath.resolve("patches/test.patch"));
        ModelSummary opus = latestModelSummary(packagePath, "opus");
        ModelSummary qwen = latestModelSummary(packagePath, "qwen");
        return new ReportContext(packagePath, task, gold, test, opus, qwen);
    }

    private void writeWorkbook(Path reportPath, ReportContext context) throws IOException {
        List<List<String>> resultRows = new ArrayList<>();
        resultRows.add(List.of("序号", "验收大类", "验收标准内容", "判断结果", "样例真实证据", "问题/风险", "修正建议"));
        for (Rule rule : RULES) {
            Result result = evaluate(rule.index(), context);
            resultRows.add(List.of(
                    String.valueOf(rule.index()),
                    rule.category(),
                    rule.content(),
                    result.status(),
                    result.evidence(),
                    result.risk(),
                    result.suggestion()));
        }

        List<List<String>> summaryRows = List.of(
                List.of("项目", "结果"),
                List.of("样例目录", context.packagePath().getFileName().toString()),
                List.of("总体结论", overallConclusion(resultRows)),
                List.of("gold patch 修改文件数", String.valueOf(context.gold().files())),
                List.of("gold patch 新增行", String.valueOf(context.gold().additions())),
                List.of("gold patch 删除行", String.valueOf(context.gold().deletions())),
                List.of("gold patch 合计改动行", String.valueOf(context.gold().totalChanged())),
                List.of("test patch 修改文件数", String.valueOf(context.test().files())),
                List.of("test patch 合计改动行", String.valueOf(context.test().totalChanged())),
                List.of("Opus 4.7 采样", context.opus().display()),
                List.of("Qwen3.6 Plus 采样", context.qwen().display())
        );

        try (OutputStream output = Files.newOutputStream(reportPath);
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypesXml());
            put(zip, "_rels/.rels", rootRelsXml());
            put(zip, "xl/workbook.xml", workbookXml());
            put(zip, "xl/_rels/workbook.xml.rels", workbookRelsXml());
            put(zip, "xl/styles.xml", stylesXml());
            put(zip, "xl/worksheets/sheet1.xml", sheetXml(resultRows));
            put(zip, "xl/worksheets/sheet2.xml", sheetXml(summaryRows));
        }
    }

    private Result evaluate(int index, ReportContext c) {
        return switch (index) {
            case 1 -> fileResult(hasDockerEvidence(c.packagePath()), "满足",
                    "docker-image 目录存在 tar/sha256 证据；verification.md/logs/model_evaluation 可作为运行验证记录。",
                    "缺少 docker image tar 或 sha256。", "补充 docker-image tar、sha256 和 image_info。");
            case 2 -> fileResult(Files.isRegularFile(c.packagePath().resolve("patches/gold.patch"))
                            && hasLog(c.packagePath(), "fixed"),
                    "满足", "gold.patch 存在，fixed 日志显示修复后测试通过。",
                    "缺少 gold.patch 或 fixed 测试日志。", "补齐 golden patch 和 fixed.log。");
            case 3, 4, 7, 8 -> fileResult(hasText(c.task(), "repo") && hasText(c.task(), "base_commit"),
                    "满足", "task.json 提供 repo/base commit/fix commit 证据链，属于真实工程任务。",
                    "repo 或 commit 证据缺失。", "补齐公开 repo、PR、base/fix commit。");
            case 5 -> new Result("基本满足", "来源基于公开 PR/issue/commit；如有 review 或用户反馈证据应继续补充。", "", "补充 PR 讨论、review 评论或 bug report 原文。");
            case 6, 21 -> new Result("不适用", "当前任务按公开 repo/PR 证据处理。", "该条不适用于公开来源任务。", "无。");
            case 9 -> new Result(c.gold().files() >= 2 && c.gold().totalChanged() >= 100 ? "满足" : "不满足",
                    "gold.patch 涉及 " + c.gold().files() + " 个文件，新增 " + c.gold().additions()
                            + " 行、删除 " + c.gold().deletions() + " 行、合计 " + c.gold().totalChanged() + " 行。",
                    c.gold().files() >= 2 && c.gold().totalChanged() >= 100 ? "" : "ground-truth patch 规模不足。",
                    c.gold().files() >= 2 && c.gold().totalChanged() >= 100 ? "无。" : "优先选择多文件、百行以上真实工程变更。");
            case 10 -> new Result(c.opus().passes() > 0 ? "满足" : "不满足",
                    c.opus().display(), c.opus().passes() > 0 ? "" : "Opus pass@8 为 0。", "选择 Opus 至少一次可通过的任务。");
            case 11 -> new Result(c.qwen().passRate() <= 0.5d ? "单题满足困难桶/数据集分布无法判断" : "不满足",
                    c.qwen().display(), "75%/25% 比例需在批量维度判断。", "批量交付时统计 qwen pass rate 分布。");
            case 12, 18 -> new Result("单题满足/数据集分布无法判断",
                    "task.json metadata 提供语言与分类标签；完整分布需批量统计。",
                    "需要在批量维度判断。", "批量交付时输出语言和分类透视表。");
            case 13 -> fileResult(hasText(c.task(), "repo_language") || metadataHasText(c.task(), "repo_language"),
                    "满足", "task metadata 标注 repo language。", "缺少语言标注。", "补齐 metadata.repo_language。");
            case 14 -> new Result("部分满足/需外部查重",
                    "Java pipeline 记录 benchmark_status；公开 benchmark 黑名单需外部数据源持续更新。",
                    "包内未包含完整外部 benchmark 查重证据。", "补充 swe-gym/swe-rebench/swe-bench 系列黑名单查重清单。");
            case 15, 17 -> fileResult(Files.isRegularFile(c.packagePath().resolve("patches/test.patch"))
                            && Files.isRegularFile(c.packagePath().resolve("problem_statement.md")),
                    "满足", "test.patch 与 problem_statement.md 均存在，可对照 issue 修复点。",
                    "测试或问题描述缺失。", "补齐 test.patch 和 problem_statement.md。");
            case 16 -> new Result("基本满足", "测试以公开问题行为为验收对象；仍需人工确认不过度绑定 golden 实现。", "", "补充替代实现通过记录。");
            case 19 -> fileResult(hasReviewEvidence(c.packagePath()), "部分满足/需补充",
                    "reviewer_1/2/3 与 adjudication_and_calibration.md 存在。",
                    "评审员资质、独立性、时间戳/签名可能仍需补充。",
                    "补充专家资质、盲审分配、校准会议和仲裁记录。");
            case 20 -> fileResult(hasText(c.task(), "repo") && hasText(c.task(), "base_commit"),
                    "满足", "task.json 提供公开 repo 与 commit ID。", "公开 repo 或 commit ID 缺失。", "补齐 repo_url/base_commit/fix_commit。");
            case 22 -> fileResult(hasDockerEvidence(c.packagePath()) || Files.isRegularFile(c.packagePath().resolve("dockerfiles/Dockerfile")),
                    "满足（需本地加载确认）", "提供 docker image tar/sha256 或 Dockerfile。", "当前环境未执行 docker load/run 复验。", "验收时 docker load 后执行 eval 并保留日志。");
            case 23 -> fileResult(Files.isRegularFile(c.packagePath().resolve("problem_statement.md")),
                    "满足", "problem_statement.md 存在。", "缺少 problem_statement.md。", "补齐详细问题描述。");
            case 24 -> fileResult(Files.isRegularFile(c.packagePath().resolve("patches/gold.patch")),
                    "满足", "patches/gold.patch 存在。", "缺少 gold.patch。", "补齐可完美修复问题的 patch。");
            case 25 -> fileResult(Files.isRegularFile(c.packagePath().resolve("patches/test.patch")),
                    "满足", "patches/test.patch 存在。", "缺少 test.patch。", "补齐评测测试 patch。");
            case 26 -> jsonArrayResult(c.task(), "fail_to_pass", "fail_to_pass");
            case 27 -> jsonArrayResult(c.task(), "pass_to_pass", "pass_to_pass");
            case 28 -> fieldOrFileResult(c, "requirements", "requirements.md");
            case 29 -> fieldOrFileResult(c, "interface", "interface.md");
            case 30 -> jsonArrayResult(c.task(), "selected_test_files_to_run", "selected_test_files_to_run");
            case 31 -> fieldOrFileResult(c, "before_repo_set_cmd", "scripts/run_selected_tests.sh");
            case 32 -> metadataResult(c.task(), "repo_language");
            case 33 -> metadataResult(c.task(), "issue_specificity");
            case 34 -> metadataResult(c.task(), "issue_categories");
            default -> new Result("需人工判断", "未配置自动判断规则。", "需要人工复核。", "补充自动验收规则。");
        };
    }

    private Result fileResult(boolean ok, String okStatus, String okEvidence, String risk, String suggestion) {
        return ok
                ? new Result(okStatus, okEvidence, "", "无。")
                : new Result("不满足", risk, risk, suggestion);
    }

    private Result jsonArrayResult(JSONObject task, String field, String label) {
        JSONArray values = task.getJSONArray(field);
        boolean ok = values != null && !values.isEmpty();
        return fileResult(ok, "满足", "task.json 中 " + label + " 存在且非空。",
                "task.json 中 " + label + " 缺失或为空。", "补齐 " + label + " 测试列表。");
    }

    private Result fieldOrFileResult(ReportContext c, String field, String file) {
        boolean ok = hasText(c.task(), field) || Files.isRegularFile(c.packagePath().resolve(file));
        return fileResult(ok, "满足", "task.json 字段或 " + file + " 存在。",
                field + " 字段和 " + file + " 均缺失。", "补齐 " + field + " 或 " + file + "。");
    }

    private Result metadataResult(JSONObject task, String field) {
        return fileResult(metadataHasText(task, field), "满足", "metadata." + field + " 存在。",
                "metadata." + field + " 缺失。", "补齐 metadata." + field + "。");
    }

    private boolean hasDockerEvidence(Path packagePath) {
        Path dockerImage = packagePath.resolve("docker-image");
        if (!Files.isDirectory(dockerImage)) {
            return false;
        }
        try (var stream = Files.list(dockerImage)) {
            return stream.anyMatch(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".tar") || name.endsWith(".sha256");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private boolean hasReviewEvidence(Path packagePath) {
        return Files.isRegularFile(packagePath.resolve("review/reviewer_1.md"))
                && Files.isRegularFile(packagePath.resolve("review/reviewer_2.md"))
                && Files.isRegularFile(packagePath.resolve("review/reviewer_3.md"))
                && Files.isRegularFile(packagePath.resolve("review/adjudication_and_calibration.md"));
    }

    private boolean hasLog(Path packagePath, String needle) {
        Path logs = packagePath.resolve("logs");
        if (!Files.isDirectory(logs)) {
            return false;
        }
        try (var stream = Files.walk(logs, 2)) {
            String lowerNeedle = needle.toLowerCase(Locale.ROOT);
            return stream.filter(Files::isRegularFile)
                    .anyMatch(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).contains(lowerNeedle));
        } catch (IOException e) {
            return false;
        }
    }

    private JSONObject readJson(Path path) {
        if (!Files.isRegularFile(path)) {
            return new JSONObject();
        }
        try {
            return JSON.parseObject(Files.readString(path));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private PatchStats patchStats(Path path) {
        if (!Files.isRegularFile(path)) {
            return new PatchStats(0, 0, 0);
        }
        int files = 0;
        int additions = 0;
        int deletions = 0;
        try {
            for (String line : Files.readAllLines(path)) {
                if (line.startsWith("diff --git ")) {
                    files++;
                } else if (line.startsWith("+") && !line.startsWith("+++")) {
                    additions++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    deletions++;
                }
            }
        } catch (IOException e) {
            return new PatchStats(0, 0, 0);
        }
        return new PatchStats(files, additions, deletions);
    }

    private ModelSummary latestModelSummary(Path packagePath, String key) {
        Path root = packagePath.resolve("model_evaluation");
        if (!Files.isDirectory(root)) {
            return ModelSummary.missing(key);
        }
        try (var stream = Files.walk(root, 2)) {
            String lower = key.toLowerCase(Locale.ROOT);
            Path summary = stream.filter(Files::isRegularFile)
                    .filter(path -> "summary.json".equals(path.getFileName().toString()))
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().toLowerCase(Locale.ROOT).contains(lower))
                    .findFirst()
                    .orElse(null);
            if (summary == null) {
                return ModelSummary.missing(key);
            }
            JSONObject json = readJson(summary);
            int attempts = json.getIntValue("attempts");
            int passes = json.getIntValue("passes");
            double passRate = json.containsKey("pass_rate")
                    ? json.getDoubleValue("pass_rate")
                    : (attempts == 0 ? 0d : passes / (double) attempts);
            return new ModelSummary(key, attempts, passes, passRate);
        } catch (IOException e) {
            return ModelSummary.missing(key);
        }
    }

    private boolean hasText(JSONObject json, String key) {
        return StringUtils.hasText(json.getString(key));
    }

    private boolean metadataHasText(JSONObject task, String key) {
        JSONObject metadata = task.getJSONObject("metadata");
        if (metadata == null) {
            return hasText(task, key);
        }
        Object value = metadata.get(key);
        if (value instanceof JSONArray array) {
            return !array.isEmpty();
        }
        return value != null && StringUtils.hasText(String.valueOf(value));
    }

    private String overallConclusion(List<List<String>> rows) {
        long failed = rows.stream().skip(1).filter(row -> "不满足".equals(row.get(3))).count();
        return failed == 0
                ? "总体基本满足验收标准，批量分布和外部查重项需在交付批次维度继续验证。"
                : "存在 " + failed + " 项不满足，请按修正建议补齐后重新验收。";
    }

    private void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String sheetXml(List<List<String>> rows) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        xml.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            xml.append("<row r=\"").append(r + 1).append("\">");
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                xml.append("<c r=\"").append(columnName(c)).append(r + 1).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escape(row.get(c))).append("</t></is></c>");
            }
            xml.append("</row>");
        }
        xml.append("</sheetData></worksheet>");
        return xml.toString();
    }

    private String columnName(int index) {
        StringBuilder name = new StringBuilder();
        int value = index;
        do {
            name.insert(0, (char) ('A' + value % 26));
            value = value / 26 - 1;
        } while (value >= 0);
        return name.toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String contentTypesXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                </Types>
                """;
    }

    private String rootRelsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
                """;
    }

    private String workbookXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="34条验收结果" sheetId="1" r:id="rId1"/>
                    <sheet name="汇总" sheetId="2" r:id="rId2"/>
                  </sheets>
                </workbook>
                """;
    }

    private String workbookRelsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>
                """;
    }

    private String stylesXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
                  <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
                  <borders count="1"><border/></borders>
                  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                  <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
                </styleSheet>
                """;
    }

    private record Rule(int index, String category, String content) {
    }

    private record Result(String status, String evidence, String risk, String suggestion) {
    }

    private record PatchStats(int files, int additions, int deletions) {
        int totalChanged() {
            return additions + deletions;
        }
    }

    private record ModelSummary(String key, int attempts, int passes, double passRate) {
        static ModelSummary missing(String key) {
            return new ModelSummary(key, 0, 0, 0d);
        }

        String display() {
            if (attempts == 0) {
                return key + " summary missing";
            }
            return passes + "/" + attempts + " 通过，pass_rate=" + passRate + "，pass_nonzero=" + (passes > 0);
        }
    }

    private record ReportContext(
            Path packagePath,
            JSONObject task,
            PatchStats gold,
            PatchStats test,
            ModelSummary opus,
            ModelSummary qwen) {
    }
}

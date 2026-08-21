package com.ai.demo.splitter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 结构感知的 Markdown 切分器。
 * 策略：按标题层级切块（第一层）→ 超限块按空行分段（第二层）→ 仍超限委托 TokenTextSplitter（第三层）。
 * 每个块的开头带"归属路径"前缀（contextual chunking）。
 */
@Slf4j
public class MarkDownWordSplitter extends TextSplitter {

    /** 切块层级：遇到层级 <= cutLevel 的标题时结算旧块、开新块 */
    private final int cutLevel;

    /** 单块最大字符数，超过则进入第二层细分 */
    private final int maxChunkSize;

    /** 第三层兜底：组合而非继承（我用它的能力，但我不是它） */
    private final TokenTextSplitter fallback = TokenTextSplitter.builder().build();

    public MarkDownWordSplitter() {
        this(2, 800);
    }

    public MarkDownWordSplitter(int cutLevel, int maxChunkSize) {
        this.cutLevel = cutLevel;
        this.maxChunkSize = maxChunkSize;
    }

    /** 标题栈元素：必须同时存层级和文字，否则"弹出所有 ≥n 的"无法判断 */
    private record Heading(int level, String title) {
    }

    @Override
    protected List<String> splitText(String text) {
        List<String> chunks = new ArrayList<>();
        Deque<Heading> stack = new ArrayDeque<>();   // 栈底 = 最高层标题，栈顶 = 最近标题
        StringBuilder current = new StringBuilder();

        for (String line : text.lines().toList()) {
            int level = headingLevel(line);

            if (level > 0) {
                // ---- 标题行 ----
                String title = line.substring(level).trim();

                // 规则 1：到达切块边界 → 先结算旧块（非空才入列，防空块）
                if (level <= cutLevel && !current.isEmpty()) {
                    chunks.add(current.toString());
                    current = new StringBuilder();
                }

                // 规则 2：维护标题栈——弹出所有层级 >= 当前标题的，再压入
                while (!stack.isEmpty() && stack.peek().level() >= level) {
                    stack.pop();
                }
                stack.push(new Heading(level, title));

                if (level <= cutLevel) {
                    // 规则 3：新块开头写入归属路径前缀
                    current.append(pathPrefix(stack)).append("\n");
                } else {
                    // 深层标题不切块，但标题行本身是内容，不能丢
                    current.append(line).append("\n");
                }
            } else {
                // ---- 普通行：追加到当前块 ----
                current.append(line).append("\n");
            }
        }

        // 收尾：结算最后一块（fencepost bug 的修复点）
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }

        // 第二层 + 第三层：对超限块继续细分
        return chunks.stream()
                .flatMap(c -> splitOversized(c).stream())
                .toList();
    }

    /**
     * 第二层：按空行把段落贪心合并到接近阈值（是"合并"不是"拆散"），
     * 每个子块重新带上路径前缀；单个段落仍超限才委托 TokenTextSplitter（第三层）。
     */
    private List<String> splitOversized(String chunk) {
        if (chunk.length() <= maxChunkSize) {
            return List.of(chunk);
        }
        // 提取块头的路径前缀，子块要继承它
        String prefix = "";
        String body = chunk;
        int firstNewline = chunk.indexOf('\n');
        if (chunk.startsWith("【") && firstNewline > 0) {
            prefix = chunk.substring(0, firstNewline + 1);
            body = chunk.substring(firstNewline + 1);
        }

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder(prefix);
        final String chunkPrefix = prefix;   // lambda 捕获需要 effectively final
        for (String para : body.split("\n\\s*\n")) {
            if (para.isBlank()) {
                continue;
            }
            if (para.length() > maxChunkSize) {
                // 单段就超限：先结算手头的，再走第三层兜底
                if (current.length() > chunkPrefix.length()) {
                    result.add(current.toString());
                    current = new StringBuilder(chunkPrefix);
                }
                fallback.split(new Document(para)).stream()
                        .map(d -> chunkPrefix + d.getText())
                        .forEach(result::add);
                continue;
            }
            // 装不下当前段落就结算，开新子块（同样带前缀）
            if (current.length() + para.length() > maxChunkSize && current.length() > chunkPrefix.length()) {
                result.add(current.toString());
                current = new StringBuilder(chunkPrefix);
            }
            current.append(para).append("\n");
        }
        if (current.length() > chunkPrefix.length()) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * 行首连续 # 的个数；非标题行返回 0。
     * 注意：只数行首（正文的 # 不算），且 # 后必须有空格（Markdown 标准）。
     */
    private int headingLevel(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') {
            n++;
        }
        return (n > 0 && n < line.length() && line.charAt(n) == ' ') ? n : 0;
    }

    /** 栈底到栈顶拼成 "章 > 节 > 小节" 路径 */
    private String pathPrefix(Deque<Heading> stack) {
        List<String> titles = new ArrayList<>();
        stack.descendingIterator().forEachRemaining(h -> titles.add(h.title()));
        return "【" + String.join(" > ", titles) + "】";
    }

    public static void main(String[] args) {
        try {
            String md = Files.readString(Path.of("ReAct01.md"));
            MarkDownWordSplitter splitter = new MarkDownWordSplitter();
            List<String> chunks = splitter.splitText(md);
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                log.info("=== 块 {} ===\n{}\n", i, chunk.length() > 80 ? chunk.substring(0, 80) : chunk);
            }
        } catch (IOException e) {
            log.error("Markdown 切分失败", e);
        }
    }

}

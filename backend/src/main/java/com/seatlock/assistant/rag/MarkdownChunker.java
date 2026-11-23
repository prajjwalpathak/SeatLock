package com.seatlock.assistant.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a policy file at "## " headings: one chunk per section, prefixed with the document title so a chunk
 * still makes sense on its own ("Cancellation and refund policy: When cancellation closes. Less than 24 hours...").
 * Text before the first "## " becomes a chunk headed by the title (if it has any content).
 */
public final class MarkdownChunker {

    private MarkdownChunker() {
    }

    public static List<PolicyChunk> chunk(String doc, String markdown) {
        String title = doc;
        List<PolicyChunk> chunks = new ArrayList<>();
        String heading = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.replace("\r\n", "\n").split("\n")) {
            if (line.startsWith("# ")) {
                title = line.substring(2).strip();
            } else if (line.startsWith("## ")) {
                add(chunks, doc, title, heading, body);
                heading = line.substring(3).strip();
                body.setLength(0);
            } else {
                body.append(line.strip()).append(' ');
            }
        }
        add(chunks, doc, title, heading, body);
        return chunks;
    }

    private static void add(List<PolicyChunk> chunks, String doc, String title, String heading, StringBuilder body) {
        String text = body.toString().strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return;
        }
        String h = heading == null ? title : heading;
        chunks.add(new PolicyChunk(doc, chunks.size(), h, title + ": " + h + ". " + text));
    }
}

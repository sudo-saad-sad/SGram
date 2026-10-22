package org.telegram.messenger.matrix;

import android.text.TextUtils;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MatrixTextHelper {

    public static class ParsedMatrixText {
        public String plainText;
        public ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
    }

    /**
     * Converts Telegram entities to Matrix HTML formatted_body.
     */
    public static String entitiesToMatrixHtml(String text, ArrayList<TLRPC.MessageEntity> entities) {
        if (TextUtils.isEmpty(text) || entities == null || entities.isEmpty()) {
            return null;
        }

        // Sort entities by offset ascending
        ArrayList<TLRPC.MessageEntity> sorted = new ArrayList<>(entities);
        Collections.sort(sorted, (e1, e2) -> Integer.compare(e1.offset, e2.offset));

        StringBuilder sb = new StringBuilder();
        int lastIndex = 0;
        int textLen = text.length();

        for (TLRPC.MessageEntity entity : sorted) {
            int start = Math.max(0, Math.min(entity.offset, textLen));
            int end = Math.max(start, Math.min(entity.offset + entity.length, textLen));

            if (start > lastIndex) {
                escapeHtml(sb, text.substring(lastIndex, start));
            }

            String innerText = text.substring(start, end);
            StringBuilder innerEscaped = new StringBuilder();
            escapeHtml(innerEscaped, innerText);

            if (entity instanceof TLRPC.TL_messageEntityBold) {
                sb.append("<b>").append(innerEscaped).append("</b>");
            } else if (entity instanceof TLRPC.TL_messageEntityItalic) {
                sb.append("<i>").append(innerEscaped).append("</i>");
            } else if (entity instanceof TLRPC.TL_messageEntityCode) {
                sb.append("<code>").append(innerEscaped).append("</code>");
            } else if (entity instanceof TLRPC.TL_messageEntityPre) {
                TLRPC.TL_messageEntityPre pre = (TLRPC.TL_messageEntityPre) entity;
                if (!TextUtils.isEmpty(pre.language)) {
                    sb.append("<pre><code class=\"language-").append(pre.language).append("\">").append(innerEscaped).append("</code></pre>");
                } else {
                    sb.append("<pre><code>").append(innerEscaped).append("</code></pre>");
                }
            } else if (entity instanceof TLRPC.TL_messageEntityStrike) {
                sb.append("<del>").append(innerEscaped).append("</del>");
            } else if (entity instanceof TLRPC.TL_messageEntityUnderline) {
                sb.append("<u>").append(innerEscaped).append("</u>");
            } else if (entity instanceof TLRPC.TL_messageEntitySpoiler) {
                sb.append("<span data-mx-spoiler>").append(innerEscaped).append("</span>");
            } else if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                TLRPC.TL_messageEntityTextUrl urlEntity = (TLRPC.TL_messageEntityTextUrl) entity;
                sb.append("<a href=\"").append(urlEntity.url != null ? urlEntity.url : "").append("\">").append(innerEscaped).append("</a>");
            } else {
                sb.append(innerEscaped);
            }

            lastIndex = end;
        }

        if (lastIndex < textLen) {
            escapeHtml(sb, text.substring(lastIndex));
        }

        return sb.toString();
    }

    private static void escapeHtml(StringBuilder sb, String text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c); break;
            }
        }
    }

    /**
     * Parses Matrix HTML formatted_body into plain text and Telegram entities.
     */
    public static ParsedMatrixText parseMatrixHtml(String html) {
        ParsedMatrixText result = new ParsedMatrixText();
        if (TextUtils.isEmpty(html)) {
            result.plainText = "";
            return result;
        }

        StringBuilder plain = new StringBuilder();
        Pattern tagPattern = Pattern.compile("(?i)<(/?[a-z0-9_-]+)([^>]*)>");
        Matcher matcher = tagPattern.matcher(html);

        int lastEnd = 0;
        java.util.Stack<TagInfo> stack = new java.util.Stack<>();

        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();

            // Append plain text before tag
            if (start > lastEnd) {
                String sub = html.substring(lastEnd, start);
                plain.append(unescapeHtml(sub));
            }

            String tagName = matcher.group(1).toLowerCase();
            String attrs = matcher.group(2);

            if (tagName.startsWith("/")) {
                // Closing tag
                String closingTag = tagName.substring(1);
                for (int i = stack.size() - 1; i >= 0; i--) {
                    TagInfo info = stack.get(i);
                    if (info.tagName.equals(closingTag)) {
                        stack.remove(i);
                        int entityStart = info.plainStartIndex;
                        int entityLength = plain.length() - entityStart;
                        if (entityLength > 0) {
                            TLRPC.MessageEntity entity = createEntity(info.tagName, info.attrs, entityStart, entityLength);
                            if (entity != null) {
                                result.entities.add(entity);
                            }
                        }
                        break;
                    }
                }
            } else {
                // Opening tag
                if ("br".equals(tagName) || "br/".equals(tagName)) {
                    plain.append("\n");
                } else {
                    TagInfo info = new TagInfo();
                    info.tagName = tagName;
                    info.attrs = attrs != null ? attrs : "";
                    info.plainStartIndex = plain.length();
                    stack.push(info);
                }
            }

            lastEnd = end;
        }

        if (lastEnd < html.length()) {
            plain.append(unescapeHtml(html.substring(lastEnd)));
        }

        result.plainText = plain.toString();
        return result;
    }

    private static class TagInfo {
        String tagName;
        String attrs;
        int plainStartIndex;
    }

    private static TLRPC.MessageEntity createEntity(String tag, String attrs, int offset, int length) {
        switch (tag) {
            case "b":
            case "strong": {
                TLRPC.TL_messageEntityBold bold = new TLRPC.TL_messageEntityBold();
                bold.offset = offset;
                bold.length = length;
                return bold;
            }
            case "i":
            case "em": {
                TLRPC.TL_messageEntityItalic italic = new TLRPC.TL_messageEntityItalic();
                italic.offset = offset;
                italic.length = length;
                return italic;
            }
            case "code": {
                TLRPC.TL_messageEntityCode code = new TLRPC.TL_messageEntityCode();
                code.offset = offset;
                code.length = length;
                return code;
            }
            case "pre": {
                TLRPC.TL_messageEntityPre pre = new TLRPC.TL_messageEntityPre();
                pre.offset = offset;
                pre.length = length;
                pre.language = "";
                return pre;
            }
            case "del":
            case "s":
            case "strike": {
                TLRPC.TL_messageEntityStrike strike = new TLRPC.TL_messageEntityStrike();
                strike.offset = offset;
                strike.length = length;
                return strike;
            }
            case "u": {
                TLRPC.TL_messageEntityUnderline u = new TLRPC.TL_messageEntityUnderline();
                u.offset = offset;
                u.length = length;
                return u;
            }
            case "span": {
                if (attrs.contains("data-mx-spoiler") || attrs.contains("spoiler")) {
                    TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
                    spoiler.offset = offset;
                    spoiler.length = length;
                    return spoiler;
                }
                break;
            }
            case "a": {
                String href = extractAttr(attrs, "href");
                if (!TextUtils.isEmpty(href)) {
                    TLRPC.TL_messageEntityTextUrl url = new TLRPC.TL_messageEntityTextUrl();
                    url.offset = offset;
                    url.length = length;
                    url.url = href;
                    return url;
                }
                break;
            }
        }
        return null;
    }

    private static String extractAttr(String attrs, String name) {
        if (TextUtils.isEmpty(attrs)) return "";
        Pattern p = Pattern.compile(name + "\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(attrs);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    private static String unescapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'")
                   .replace("&nbsp;", " ");
    }
}

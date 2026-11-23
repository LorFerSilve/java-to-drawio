package io.github.lorfersilve.javatodrawio;

import io.github.lorfersilve.javatodrawio.DrawioReader.Cell;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

final class DrawioLabel extends HTMLEditorKit.ParserCallback {
    record Line(String text, boolean italic, boolean underline) {
        Line withText(String value) {
            return new Line(value, italic, underline);
        }

        Line append(Line other, boolean generated) {
            boolean wrappedWord = generated && text.length() % DrawioWriter.MAX_COLUMNS == 0 && !text.endsWith(",");
            return new Line(text + (wrappedWord ? "" : " ") + other.text,
                    italic || other.italic, underline || other.underline);
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private final int fontStyle;
    private int italic;
    private int underline;
    private boolean lineItalic;
    private boolean lineUnderline;

    private DrawioLabel(int fontStyle) {
        this.fontStyle = fontStyle;
    }

    static List<Line> lines(Cell cell) {
        int fontStyle = fontStyle(cell);
        if (!cell.styled("html", "1")) {
            return cell.label().lines().map(String::strip).filter(line -> !line.isEmpty())
                    .map(line -> new Line(line, (fontStyle & 2) != 0, (fontStyle & 4) != 0)).toList();
        }
        var callback = new DrawioLabel(fontStyle);
        try {
            String html = cell.label().replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
            new ParserDelegator().parse(new StringReader(html), callback, true);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read the shape label", e);
        }
        callback.lineBreak();
        return callback.lines;
    }

    private static int fontStyle(Cell cell) {
        try {
            return Integer.parseInt(cell.style().getOrDefault("fontStyle", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void handleText(char[] characters, int position) {
        text.append(characters);
        if (!String.valueOf(characters).isBlank()) {
            lineItalic |= italic > 0 || (fontStyle & 2) != 0;
            lineUnderline |= underline > 0 || (fontStyle & 4) != 0;
        }
    }

    @Override
    public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
        if (block(tag)) {
            lineBreak();
        }
        if (tag == HTML.Tag.I || tag == HTML.Tag.EM) {
            italic++;
        } else if (tag == HTML.Tag.U) {
            underline++;
        }
    }

    @Override
    public void handleEndTag(HTML.Tag tag, int position) {
        if (block(tag)) {
            lineBreak();
        }
        if (tag == HTML.Tag.I || tag == HTML.Tag.EM) {
            italic--;
        } else if (tag == HTML.Tag.U) {
            underline--;
        }
    }

    @Override
    public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
        if (tag == HTML.Tag.BR || tag == HTML.Tag.HR) {
            lineBreak();
        }
    }

    private static boolean block(HTML.Tag tag) {
        return tag == HTML.Tag.DIV || tag == HTML.Tag.P || tag == HTML.Tag.LI || tag == HTML.Tag.TR;
    }

    private void lineBreak() {
        String line = text.toString().replace('\u00a0', ' ').strip();
        if (!line.isEmpty()) {
            lines.add(new Line(line, lineItalic, lineUnderline));
        }
        text.setLength(0);
        lineItalic = false;
        lineUnderline = false;
    }
}

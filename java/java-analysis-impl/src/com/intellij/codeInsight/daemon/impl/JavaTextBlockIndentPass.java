// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.daemon.impl;

import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.impl.source.tree.java.PsiLiteralExpressionImpl;
import com.intellij.ui.paint.LinePainter2D;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.intellij.util.ObjectUtils.tryCast;

public class JavaTextBlockIndentPass extends TextEditorHighlightingPass {

  private static final StringContentIndentRenderer RENDERER = new StringContentIndentRenderer();

  private final Editor myEditor;
  private final PsiJavaFile myFile;

  private List<StringContentIndent> myIndents = Collections.emptyList();

  @Contract(pure = true)
  public JavaTextBlockIndentPass(@NotNull Project project, @NotNull Editor editor, @NotNull PsiJavaFile file) {
    super(project, editor.getDocument());
    myEditor = editor;
    myFile = file;
  }

  @Override
  public void doCollectInformation(@NotNull ProgressIndicator progress) {
    if (!myEditor.getSettings().isIndentGuidesShown()) {
      myIndents = Collections.emptyList();
      return;
    }
    Document document = myEditor.getDocument();
    IndentCollector collector = new IndentCollector(document);
    myFile.accept(collector);
    myIndents = collector.getIndents();
  }

  @Override
  public void doApplyInformationToEditor() {
    if (!StringContentIndentUtil.isDocumentUpdated(myEditor)) return;
    MarkupModel model = myEditor.getMarkupModel();
    Map<TextRange, RangeHighlighter> oldHighlighters = StringContentIndentUtil.getIndentHighlighters(myEditor);
    List<RangeHighlighter> newHighlighters = new ArrayList<>();

    for (StringContentIndent indent : myIndents) {
      TextRange newRange = new TextRange(indent.startOffset, indent.endOffset);
      int newIndent = indent.column;
      // do not show guide if indent is zero or text block is invalid
      if (newIndent <= 0) continue;
      RangeHighlighter oldHighlighter = oldHighlighters.get(newRange);
      if (oldHighlighter != null && StringContentIndentUtil.getIndent(oldHighlighter) == newIndent) {
        newHighlighters.add(oldHighlighter);
        oldHighlighters.remove(newRange);
      }
      else {
        RangeHighlighter newHighlighter =
          model.addRangeHighlighter(indent.startOffset, indent.endOffset, 0, null, HighlighterTargetArea.EXACT_RANGE);
        newHighlighter.setCustomRenderer(RENDERER);
        StringContentIndentUtil.setIndent(newHighlighter, newIndent);
        newHighlighters.add(newHighlighter);
      }
    }

    oldHighlighters.values().forEach(RangeMarker::dispose);
    StringContentIndentUtil.addIndentHighlighters(myEditor, newHighlighters);

    StringContentIndentUtil.updateTimestamp(myEditor);
  }

  private static class StringContentIndent {

    private final int column;
    private final int startOffset;
    // inclusive
    private final int endOffset;

    @Contract(pure = true)
    private StringContentIndent(int column, int startOffset, int endOffset) {
      this.column = column;
      this.startOffset = startOffset;
      this.endOffset = endOffset;
    }

    @Override
    public String toString() {
      return "StringContentIndent{" +
             "column=" + column +
             ", startOffset=" + startOffset +
             ", endOffset=" + endOffset +
             '}';
    }
  }

  private static class StringContentIndentRenderer implements CustomHighlighterRenderer {

    @Override
    public void paint(@NotNull Editor editor, @NotNull RangeHighlighter highlighter, @NotNull Graphics g) {
      int indent = StringContentIndentUtil.getIndent(highlighter);
      if (indent <= 0) return;

      VisualPosition startPosition = editor.offsetToVisualPosition(highlighter.getStartOffset());
      Point start = editor.visualPositionToXY(new VisualPosition(startPosition.line, indent - 1));

      Point right = editor.visualPositionToXY(new VisualPosition(startPosition.line, indent));
      float x = (start.x + right.x) / 2f;

      VisualPosition endPosition = editor.offsetToVisualPosition(highlighter.getEndOffset());
      Point end = editor.visualPositionToXY(new VisualPosition(endPosition.line, indent - 1));

      EditorColorsScheme scheme = editor.getColorsScheme();
      g.setColor(scheme.getColor(EditorColors.STRING_CONTENT_INDENT_GUIDE_COLOR));
      g.setFont(scheme.getFont(EditorFontType.PLAIN));

      FontMetrics fontMetrics = g.getFontMetrics();
      int ascent = fontMetrics.getAscent();
      int descent = fontMetrics.getDescent();
      float baseline = editor.getAscent();

      float startY = start.y + baseline - ascent;
      float endY = end.y + baseline + descent;

      LinePainter2D.paint((Graphics2D)g, x, startY, x, endY);
    }
  }

  private static class IndentCollector extends JavaRecursiveElementWalkingVisitor {

    private final Document myDocument;
    private final List<StringContentIndent> myIndents = new ArrayList<>();

    private IndentCollector(@NotNull Document document) {
      myDocument = document;
    }

    @Override
    public void visitLiteralExpression(PsiLiteralExpression expression) {
      TextBlockModel model = TextBlockModel.create(expression);
      if (model == null) return;
      TextRange contentRange = getContentRange(model.myRange);
      if (contentRange == null) return;
      myIndents.add(new StringContentIndent(model.myBaseIndent, contentRange.getStartOffset(), contentRange.getEndOffset()));
    }

    @Nullable
    private TextRange getContentRange(@NotNull TextRange blockRange) {
      int nStartLine = myDocument.getLineNumber(blockRange.getStartOffset()) + 1;
      int nEndLine = myDocument.getLineNumber(blockRange.getEndOffset());
      TextRange lastLineRange = new TextRange(myDocument.getLineStartOffset(nEndLine), myDocument.getLineEndOffset(nEndLine));
      String lastLine = myDocument.getText(lastLineRange);
      lastLine = lastLine.substring(0, lastLine.indexOf("\"\"\""));
      if (StringUtil.skipWhitespaceForward(lastLine, 0) == lastLine.length()) nEndLine -= 1;
      if (nEndLine < nStartLine) return null;
      return new TextRange(myDocument.getLineStartOffset(nStartLine), myDocument.getLineStartOffset(nEndLine));
    }

    @Contract(pure = true)
    private List<StringContentIndent> getIndents() {
      return myIndents;
    }

    private static class TextBlockModel {
      private final int myBaseIndent;
      private final TextRange myRange;

      @Contract(pure = true)
      private TextBlockModel(int baseIndent, @NotNull TextRange range) {
        myBaseIndent = baseIndent;
        myRange = range;
      }

      @Nullable
      private static TextBlockModel create(@Nullable PsiLiteralExpression expression) {
        PsiLiteralExpressionImpl literal = tryCast(expression, PsiLiteralExpressionImpl.class);
        if (literal == null || literal.getLiteralElementType() != JavaTokenType.TEXT_BLOCK_LITERAL) return null;
        int baseIndent = literal.getTextBlockIndent();
        if (baseIndent == -1) return null;
        TextRange range = literal.getTextRange();
        if (range == null) return null;
        return new TextBlockModel(baseIndent, range);
      }

      @Override
      public String toString() {
        return "TextBlockModel{" +
               "myBaseIndent=" + myBaseIndent +
               ", myRange=" + myRange +
               '}';
      }
    }
  }
}

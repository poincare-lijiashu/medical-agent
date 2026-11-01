package com.medassist.kb.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 切片器：偏移精确、句子边界优先、重叠不越界、空输入安全。 */
class TextChunkerTest {

    @Test
    @DisplayName("短文本整段成一块，偏移与原文一致")
    void shortTextSinglePiece() {
        String text = "急性阑尾炎的典型表现为转移性右下腹痛。";
        List<TextChunker.Piece> pieces = TextChunker.split(text, 600, 80);

        assertThat(pieces).hasSize(1);
        TextChunker.Piece piece = pieces.get(0);
        assertThat(piece.seq()).isZero();
        assertThat(piece.content()).isEqualTo(text);
        assertThat(piece.start()).isZero();
        assertThat(piece.end()).isEqualTo(text.length());
        assertThat(text.substring(piece.start(), piece.end())).isEqualTo(piece.content());
    }

    @Test
    @DisplayName("超长文本按句子边界切块，且每块都能按偏移还原")
    void longTextSplitsOnSentenceBoundary() {
        String sentence = "患者应在餐后半小时服用该药物，并监测血压变化。";
        String text = sentence.repeat(20);
        List<TextChunker.Piece> pieces = TextChunker.split(text, 100, 20);

        assertThat(pieces.size()).isGreaterThan(1);
        for (TextChunker.Piece piece : pieces) {
            assertThat(text.substring(piece.start(), piece.end())).isEqualTo(piece.content());
            assertThat(piece.content().length()).isLessThanOrEqualTo(100);
            assertThat(piece.content()).endsWith("。");
        }
        // 序号连续，便于引用定位
        for (int i = 0; i < pieces.size(); i++) {
            assertThat(pieces.get(i).seq()).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("相邻块有重叠：边界处的知识不会只落在一侧")
    void adjacentPiecesOverlap() {
        String text = "第一段内容比较长需要被切开。".repeat(10);
        List<TextChunker.Piece> pieces = TextChunker.split(text, 60, 20);

        assertThat(pieces.size()).isGreaterThan(1);
        TextChunker.Piece first = pieces.get(0);
        TextChunker.Piece second = pieces.get(1);
        assertThat(second.start()).isLessThan(first.end());
    }

    @Test
    @DisplayName("空文本/纯空白返回空列表，不产生空切片")
    void blankTextReturnsEmpty() {
        assertThat(TextChunker.split(null, 600, 80)).isEmpty();
        assertThat(TextChunker.split("   \n\n  ", 600, 80)).isEmpty();
    }

    @Test
    @DisplayName("CRLF 统一为 LF，避免偏移在入库与回显之间错位")
    void normalizesLineEndings() {
        List<TextChunker.Piece> pieces = TextChunker.split("第一行\r\n第二行", 600, 80);
        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0).content()).isEqualTo("第一行\n第二行");
        assertThat(pieces.get(0).content()).doesNotContain("\r");
    }
}

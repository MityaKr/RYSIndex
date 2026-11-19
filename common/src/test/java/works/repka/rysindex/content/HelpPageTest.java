package works.repka.rysindex.content;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import works.repka.rysindex.content.HelpPage.Run;
import works.repka.rysindex.content.HelpPage.Span;
import works.repka.rysindex.content.HelpPage.Tone;

/** Перенос текста страницы справки: каждая буква и пробел шириной 1. */
class HelpPageTest {
    private static final HelpPage.Measure ONE = s -> s.codePointCount(0, s.length());

    private static List<Run> flow(int width, Span... spans) {
        List<Run> out = new ArrayList<>();
        HelpPage.flow(List.of(spans), 0, 0, width, false, out, ONE);
        return out;
    }

    private static Span body(String s) {
        return new Span(s, Tone.BODY);
    }

    @Test
    void wordsKeepPiecesWithoutSpaceTogether() {
        var words = HelpPage.words(List.of(body("Шанс "), new Span("яйца", Tone.LINK), body(", 0.4%  за уровень")));
        assertEquals(5, words.size());
        assertEquals(2, words.get(1).size(), "ссылка и запятая без пробела остаются одним словом");
        assertEquals("яйца", words.get(1).get(0).text());
        assertEquals(",", words.get(1).get(1).text());
    }

    @Test
    void wrapsByWordsAndMergesSameTone() {
        List<Run> runs = flow(9, body("один два три четыре"));
        assertEquals(3, runs.size());
        assertEquals("один два", runs.get(0).text());
        assertEquals("три", runs.get(1).text());
        assertEquals(10, runs.get(1).y(), "вторая строка на высоту строки ниже");
        assertEquals("четыре", runs.get(2).text());
    }

    @Test
    void toneChangeStartsNewRunOnSameLine() {
        List<Run> runs = flow(40, body("Даёт "), new Span("яйцо", Tone.LINK), body(" с мобов"));
        assertEquals(3, runs.size());
        assertEquals("Даёт", runs.get(0).text());
        assertEquals(5f, runs.get(1).x(), "ссылка после пробела");
        assertEquals("с мобов", runs.get(2).text());
        assertEquals(10f, runs.get(2).x());
    }

    @Test
    void longWordBreaksByLetters() {
        List<Run> runs = flow(4, body("абвгдежз"));
        assertEquals(2, runs.size());
        assertEquals("абвг", runs.get(0).text());
        assertEquals("дежз", runs.get(1).text());
    }

    @Test
    void lineCount() {
        List<Run> out = new ArrayList<>();
        assertEquals(0, HelpPage.flow(List.of(body("  ")), 0, 0, 10, false, out, ONE));
        assertEquals(2, HelpPage.flow(List.of(body("один два три")), 0, 0, 10, false, out, ONE));
    }
}

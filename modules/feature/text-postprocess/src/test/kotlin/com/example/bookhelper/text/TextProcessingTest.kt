package com.example.bookhelper.text

import com.example.bookhelper.contracts.BoundingBox
import com.example.bookhelper.contracts.OcrBlock
import com.example.bookhelper.contracts.OcrLine
import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.contracts.OcrWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextProcessingTest {
    @Test
    fun sentenceSegmenterSplitsByPunctuation() {
        val segmenter = SentenceSegmenter()
        val result = segmenter.split("Hello world. How are you?")
        assertEquals(listOf("Hello world.", "How are you?"), result)
    }

    @Test
    fun postProcessorMergesHyphenBreaks() {
        val postProcessor = TextPostProcessor()
        val merged = postProcessor.mergeHyphenBreaks(listOf("impor-", "tant", "word"))
        assertEquals(listOf("important", "word"), merged)
    }

    @Test
    fun postProcessorNormalizesSmartApostrophes() {
        val postProcessor = TextPostProcessor()
        val normalized = postProcessor.normalizeToken("  Children’s  ")
        assertEquals("children's", normalized)
    }

    @Test
    fun selectionResolverFindsWordByPoint() {
        val resolver = SelectionResolver()
        val page = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(
                        OcrLine(
                            words = listOf(OcrWord("hello", BoundingBox(0, 0, 100, 30))),
                            text = "hello",
                            boundingBox = BoundingBox(0, 0, 100, 30),
                        ),
                    ),
                    boundingBox = BoundingBox(0, 0, 100, 30),
                ),
            ),
            fullText = "hello",
        )

        val word = resolver.resolveWord(10f, 10f, page)
        assertNotNull(word)
        assertEquals("hello", word.text)
    }

    @Test
    fun selectionResolverFindsNearestWordWhenTapIsNear() {
        val resolver = SelectionResolver()
        val page = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(
                        OcrLine(
                            words = listOf(
                                OcrWord("hello", BoundingBox(0, 0, 100, 30)),
                                OcrWord("world", BoundingBox(140, 0, 240, 30)),
                            ),
                            text = "hello world",
                            boundingBox = BoundingBox(0, 0, 240, 30),
                        ),
                    ),
                    boundingBox = BoundingBox(0, 0, 240, 30),
                ),
            ),
            fullText = "hello world",
        )

        val word = resolver.resolveWord(250f, 20f, page)
        assertNotNull(word)
        assertEquals("world", word.text)
    }

    @Test
    fun selectionResolverReturnsNullWhenTapIsTooFar() {
        val resolver = SelectionResolver()
        val page = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(
                        OcrLine(
                            words = listOf(OcrWord("token", BoundingBox(10, 10, 90, 40))),
                            text = "token",
                            boundingBox = BoundingBox(10, 10, 90, 40),
                        ),
                    ),
                    boundingBox = BoundingBox(10, 10, 90, 40),
                ),
            ),
            fullText = "token",
        )

        val word = resolver.resolveWord(600f, 600f, page)
        assertNull(word)
    }

    @Test
    fun selectionResolverResolvesWordsInDragRegion() {
        val resolver = SelectionResolver()
        val page = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(
                        OcrLine(
                            words = listOf(
                                OcrWord("read", BoundingBox(10, 10, 80, 40)),
                                OcrWord("this", BoundingBox(90, 10, 150, 40)),
                                OcrWord("part", BoundingBox(160, 10, 230, 40)),
                            ),
                            text = "read this part",
                            boundingBox = BoundingBox(10, 10, 230, 40),
                        ),
                    ),
                    boundingBox = BoundingBox(10, 10, 230, 40),
                ),
            ),
            fullText = "read this part",
        )

        val words = resolver.resolveWordsInRegion(
            startX = 5f,
            startY = 5f,
            endX = 160f,
            endY = 45f,
            page = page,
        )
        assertEquals(listOf("read", "this"), words.map { it.text })
    }

    @Test
    fun selectionResolverHonorsCustomMinDragDistance() {
        val resolver = SelectionResolver()
        val page = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(
                        OcrLine(
                            words = listOf(OcrWord("token", BoundingBox(10, 10, 90, 40))),
                            text = "token",
                            boundingBox = BoundingBox(10, 10, 90, 40),
                        ),
                    ),
                    boundingBox = BoundingBox(10, 10, 90, 40),
                ),
            ),
            fullText = "token",
        )

        val words = resolver.resolveWordsInRegion(
            startX = 10f,
            startY = 10f,
            endX = 25f,
            endY = 20f,
            page = page,
            minDragDistance = 40f,
        )
        assertEquals(emptyList(), words)
    }

    @Test
    fun selectionResolverReturnsTouchedLinesInDragRegion() {
        val resolver = SelectionResolver()
        val page = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(
                        OcrLine(
                            words = listOf(
                                OcrWord("read", BoundingBox(10, 10, 70, 40)),
                                OcrWord("this", BoundingBox(80, 10, 150, 40)),
                            ),
                            text = "read this",
                            boundingBox = BoundingBox(10, 10, 150, 40),
                        ),
                        OcrLine(
                            words = listOf(
                                OcrWord("next", BoundingBox(10, 60, 70, 90)),
                                OcrWord("line", BoundingBox(80, 60, 140, 90)),
                            ),
                            text = "next line",
                            boundingBox = BoundingBox(10, 60, 140, 90),
                        ),
                    ),
                    boundingBox = BoundingBox(10, 10, 150, 90),
                ),
            ),
            fullText = "read this\nnext line",
        )

        val lines = resolver.resolveLinesInRegion(
            startX = 5f,
            startY = 5f,
            endX = 90f,
            endY = 95f,
            page = page,
        )

        assertEquals(listOf("read this", "next line"), lines.map { it.text })
    }

    @Test
    fun timedTapSelectionEngineBuildsRangeTextWithinWindow() {
        val line = OcrLine(
            words = listOf(
                OcrWord("This", BoundingBox(0, 0, 40, 20)),
                OcrWord("is", BoundingBox(50, 0, 80, 20)),
                OcrWord("book", BoundingBox(90, 0, 140, 20)),
            ),
            text = "This is book",
            boundingBox = BoundingBox(0, 0, 140, 20),
        )
        val engine = TimedTapSelectionEngine(maxIntervalMs = 1200)

        val first = engine.onTap(
            hit = WordHit(word = line.words[0], line = line, lineIndex = 0, wordIndex = 0),
            timestampMs = 1_000,
        )
        assertNull(first)

        val second = engine.onTap(
            hit = WordHit(word = line.words[2], line = line, lineIndex = 0, wordIndex = 2),
            timestampMs = 1_700,
        )
        assertNotNull(second)
        assertEquals("This is book", second.textToRead)
        assertEquals(listOf("This", "is", "book"), second.words.map { it.text })
        assertTrue(second.shouldReset)
    }

    @Test
    fun timedTapSelectionEngineRespectsUpdatedTapWindow() {
        val line = OcrLine(
            words = listOf(
                OcrWord("one", BoundingBox(0, 0, 40, 20)),
                OcrWord("two", BoundingBox(50, 0, 90, 20)),
            ),
            text = "one two",
            boundingBox = BoundingBox(0, 0, 90, 20),
        )
        val engine = TimedTapSelectionEngine(maxIntervalMs = 1200)
        engine.setMaxIntervalMs(400)

        engine.onTap(
            hit = WordHit(word = line.words[0], line = line, lineIndex = 0, wordIndex = 0),
            timestampMs = 1_000,
        )
        val selection = engine.onTap(
            hit = WordHit(word = line.words[1], line = line, lineIndex = 0, wordIndex = 1),
            timestampMs = 1_700,
        )
        assertNull(selection)
    }

    @Test
    fun timedTapSelectionEngineBuildsSelectionWhenLineIndexChanges() {
        val previousLine = OcrLine(
            words = listOf(
                OcrWord("read", BoundingBox(0, 100, 45, 125)),
                OcrWord("this", BoundingBox(50, 100, 95, 125)),
                OcrWord("book", BoundingBox(100, 100, 150, 125)),
            ),
            text = "read this book",
            boundingBox = BoundingBox(0, 98, 150, 128),
        )
        val currentLine = OcrLine(
            words = listOf(
                OcrWord("read", BoundingBox(0, 101, 45, 126)),
                OcrWord("this", BoundingBox(50, 101, 95, 126)),
                OcrWord("book", BoundingBox(100, 101, 150, 126)),
            ),
            text = "read this book",
            boundingBox = BoundingBox(0, 99, 150, 129),
        )
        val engine = TimedTapSelectionEngine(maxIntervalMs = 1200)

        val first = engine.onTap(
            hit = WordHit(word = previousLine.words[0], line = previousLine, lineIndex = 2, wordIndex = 0),
            timestampMs = 10_000,
        )
        assertNull(first)

        val second = engine.onTap(
            hit = WordHit(word = currentLine.words[2], line = currentLine, lineIndex = 5, wordIndex = 2),
            timestampMs = 10_450,
        )
        assertNotNull(second)
        assertEquals("read this book", second.textToRead)
        assertEquals(listOf("read", "this", "book"), second.words.map { it.text })
    }

    @Test
    fun timedTapSelectionEngineFallsBackToPairAcrossDifferentLines() {
        val topLine = OcrLine(
            words = listOf(OcrWord("first", BoundingBox(0, 0, 45, 25))),
            text = "first",
            boundingBox = BoundingBox(0, 0, 45, 25),
        )
        val bottomLine = OcrLine(
            words = listOf(OcrWord("second", BoundingBox(0, 180, 70, 205))),
            text = "second",
            boundingBox = BoundingBox(0, 180, 70, 205),
        )
        val engine = TimedTapSelectionEngine(maxIntervalMs = 1200)

        val first = engine.onTap(
            hit = WordHit(word = topLine.words[0], line = topLine, lineIndex = 0, wordIndex = 0),
            timestampMs = 20_000,
        )
        assertNull(first)

        val second = engine.onTap(
            hit = WordHit(word = bottomLine.words[0], line = bottomLine, lineIndex = 1, wordIndex = 0),
            timestampMs = 20_400,
        )
        assertNotNull(second)
        assertEquals("first second", second.textToRead)
        assertEquals(listOf("first", "second"), second.words.map { it.text })
    }

    @Test
    fun selectionResolverResolvesWordsBetweenHitsAcrossLines() {
        val resolver = SelectionResolver()
        val line1 = OcrLine(
            words = listOf(
                OcrWord("I", BoundingBox(0, 0, 15, 20)),
                OcrWord("like", BoundingBox(20, 0, 55, 20)),
                OcrWord("books.", BoundingBox(60, 0, 115, 20)),
            ),
            text = "I like books.",
            boundingBox = BoundingBox(0, 0, 115, 20),
        )
        val line2 = OcrLine(
            words = listOf(
                OcrWord("They", BoundingBox(0, 30, 35, 50)),
                OcrWord("help", BoundingBox(40, 30, 75, 50)),
                OcrWord("me", BoundingBox(80, 30, 100, 50)),
                OcrWord("learn.", BoundingBox(105, 30, 150, 50)),
            ),
            text = "They help me learn.",
            boundingBox = BoundingBox(0, 30, 150, 50),
        )
        val page = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(line1, line2),
                    boundingBox = BoundingBox(0, 0, 150, 50),
                ),
            ),
            fullText = "I like books. They help me learn.",
        )

        val firstHit = WordHit(word = line1.words[1], line = line1, lineIndex = 0, wordIndex = 1)
        val secondHit = WordHit(word = line2.words[2], line = line2, lineIndex = 1, wordIndex = 2)

        val words = resolver.resolveWordsBetweenHits(firstHit, secondHit, page)
        assertEquals(listOf("like", "books.", "They", "help", "me"), words.map { it.text })
    }

    @Test
    fun selectionResolverResolvesWordsBetweenHitsInReadingOrderForReverseTap() {
        val resolver = SelectionResolver()
        val line = OcrLine(
            words = listOf(
                OcrWord("one", BoundingBox(0, 0, 30, 20)),
                OcrWord("two", BoundingBox(35, 0, 65, 20)),
                OcrWord("three", BoundingBox(70, 0, 115, 20)),
                OcrWord("four", BoundingBox(120, 0, 155, 20)),
            ),
            text = "one two three four",
            boundingBox = BoundingBox(0, 0, 155, 20),
        )
        val page = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(line),
                    boundingBox = BoundingBox(0, 0, 155, 20),
                ),
            ),
            fullText = "one two three four",
        )

        val firstHit = WordHit(word = line.words[3], line = line, lineIndex = 0, wordIndex = 3)
        val secondHit = WordHit(word = line.words[1], line = line, lineIndex = 0, wordIndex = 1)

        val words = resolver.resolveWordsBetweenHits(firstHit, secondHit, page)
        assertEquals(listOf("two", "three", "four"), words.map { it.text })
    }
}

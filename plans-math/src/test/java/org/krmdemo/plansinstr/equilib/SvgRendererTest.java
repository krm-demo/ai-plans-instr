package org.krmdemo.plansinstr.equilib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class SvgRendererTest {

    private static Document parse(String svg) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }

    private static int count(Document d, String cls) {
        NodeList all = d.getElementsByTagName("g");
        int n = 0;
        for (int i = 0; i < all.getLength(); i++) {
            String c = ((Element) all.item(i)).getAttribute("class");
            if ((" " + c + " ").contains(" " + cls + " ")) n++;
        }
        return n;
    }

    private static int countSvg(Document d, String cls) {
        NodeList all = d.getElementsByTagName("svg");
        int n = 0;
        for (int i = 0; i < all.getLength(); i++) {
            String c = ((Element) all.item(i)).getAttribute("class");
            if ((" " + c + " ").contains(" " + cls + " ")) n++;
        }
        return n;
    }

    @Test
    void oneFramePerSampleAndExactlyOneDefault() throws Exception {
        for (String tier : new String[] {"L", "M", "S"}) {
            for (Sweep s : new Sweep[] {Sweep.DEMO, new Sweep(40_000, 60_000, 8_000, 10_000, 0, 30_000)}) {
                Document d = parse(SvgRenderer.render(s, tier, 48));
                assertEquals(48, count(d, "f"), tier);
                assertEquals(1, count(d, "d"), tier);
                assertEquals(1, countSvg(d, "one"), tier);
            }
        }
    }

    @Test
    void theAdaptivePictureHasEveryTierAndFramesInTheGraphsOnly() throws Exception {
        Document d = parse(SvgRenderer.render(Sweep.DEMO));
        for (String tier : new String[] {"icon-s", "icon", "tMINI", "tS", "tM", "tL"}) {
            assertEquals(1, countSvg(d, tier), tier);
        }
        assertEquals(3 * SvgRenderer.DEFAULT_FRAMES, count(d, "f"), "frames in S, M and L");
        assertEquals(3, count(d, "d"), "one default frame per graph");
        assertEquals("760", d.getDocumentElement().getAttribute("width"), "the natural size is that of L");
    }

    @Test
    void theFormulaIsInMAndLOnly() {
        Sweep s = new Sweep(40_000, 60_000, 8_000, 10_000, 25_000, 30_000);
        assertTrue(SvgRenderer.render(s, "L", 12).contains("class=\"formula\""));
        assertTrue(SvgRenderer.render(s, "M", 12).contains("class=\"formula\""));
        assertTrue(!SvgRenderer.render(s, "S", 12).contains("class=\"formula\""));
        assertTrue(!SvgRenderer.render(s, "mini", 12).contains("<text"));
    }

    @Test
    void frameCountsAreFinite() {
        assertEquals(12, SvgRenderer.frames(1));
        assertEquals(48, SvgRenderer.frames(40));
        assertEquals(96, SvgRenderer.frames(1000));
    }
}

package org.krmdemo.plansinstr.equilib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

    @Test
    void oneFrameperSampleAndExactlyOneDefault() throws Exception {
        for (String size : new String[] {"L", "M", "S"}) {
            for (Sweep s : new Sweep[] {Sweep.DEMO, new Sweep(40_000, 60_000, 8_000, 10_000, 0, 30_000)}) {
                Document d = parse(SvgRenderer.render(s, size, 48));
                assertEquals(48, count(d, "f"), size);
                assertEquals(1, count(d, "d"), size);
            }
        }
    }

    @Test
    void frameCountsAreFinite() {
        assertEquals(12, SvgRenderer.frames(1));
        assertEquals(48, SvgRenderer.frames(40));
        assertEquals(96, SvgRenderer.frames(1000));
    }

    @Test
    void lambdaAnswersSvgOr400() throws Exception {
        String ok = EquilibHandler.respond("thresholdMin=40000&thresholdMax=60000&sweptOutMax=2%2C000%2C000&size=M");
        assertTrue(ok.startsWith("{\"statusCode\":200"), ok.substring(0, 80));
        assertTrue(ok.contains("image/svg+xml"));
        String bad = EquilibHandler.respond("thresholdMin=70000&thresholdMax=60000");
        assertTrue(bad.startsWith("{\"statusCode\":400"), bad);

        String event = "{\"version\":\"2.0\",\"rawPath\":\"/\",\"rawQueryString\":\"frames=12&size=S\",\"headers\":{}}";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new EquilibHandler().handleRequest(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)), out);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\\\"f d\\\"") || out.toString(StandardCharsets.UTF_8).contains("f d"));
    }
}

package com.zmlworks.qrtransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** web/sender.html's Scheduler (run under node by tools/test-web.js) must behave exactly like SlotScheduler. */
public class WebSchedulerParityTest {
    @Test public void javaReplaysTheJsScript() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("web_sched.txt");
        assertTrue("run node tools/test-web.js first", in != null);
        SlotScheduler s = null;
        long t = 0;
        int lines = 0, scheds = 0, good = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isEmpty()) continue;
                String[] p = line.split(" ");
                if (p[0].equals("#sched")) {
                    s = new SlotScheduler(Integer.parseInt(p[1]), Integer.parseInt(p[2]), 250, p[3].equals("1"));
                    s.setMirror(p[4].equals("1"));
                    scheds++;
                    continue;
                }
                int arrow = java.util.Arrays.asList(p).indexOf("->");
                String got;
                switch (p[0]) {
                    case "start": t = Long.parseLong(p[1]); s.start(t); got = ""; break;
                    case "ack": t = Long.parseLong(p[2]); got = s.onAck(Integer.parseInt(p[1]), t) ? "1" : "0"; break;
                    case "tick": t = Long.parseLong(p[1]); got = s.tick(t) ? "1" : "0"; break;
                    case "requeue": t = Long.parseLong(p[2]); got = s.requeue(Integer.parseInt(p[1]), t) ? "1" : "0"; break;
                    default: t = Long.parseLong(p[3]); s.step(Integer.parseInt(p[1]), Integer.parseInt(p[2]), t); got = ""; break;
                }
                if (!got.isEmpty()) assertEquals(line, p[arrow - 1], got);
                if (p[0].equals("ack") && got.equals("1")) good++;
                String state = s.shown(0) + " " + s.shown(1) + " " + s.expectedTone(0) + " " + s.expectedTone(1) + " "
                        + s.ackedCount() + " " + s.rounds() + " " + s.goodAcks() + " " + s.staleAcks() + " " + s.latencyMs()
                        + " " + (s.silent(t) ? 1 : 0) + " " + s.nextDeadline(t)
                        + " " + (s.isMirror(0) ? 1 : 0) + (s.isMirror(1) ? 1 : 0) + " " + s.requeues();
                assertEquals(line, String.join(" ", java.util.Arrays.copyOfRange(p, arrow + 1, p.length)), state);
                lines++;
            }
        }
        assertEquals(10, scheds);
        assertTrue(lines > 4000);
        assertTrue("script exercises matching acks", good > 50);
    }
}

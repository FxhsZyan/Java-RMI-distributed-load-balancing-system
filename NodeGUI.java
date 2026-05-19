package loadbalancer;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  NodeGUI — Java Swing real-time dashboard for one distributed node
 * ═══════════════════════════════════════════════════════════════════
 *
 *  Layout:
 *  ┌──────────────────────────────────────────────────────────────┐
 *  │  HEADER: node name + IP + status indicator                   │
 *  ├────────────────────────┬─────────────────────────────────────┤
 *  │  THIS NODE stats panel │  PEER NODES panel (2 peer cards)    │
 *  │  • load bar            │  • load bar per peer                │
 *  │  • active / done / fwd │  • online / offline badge           │
 *  ├────────────────────────┴─────────────────────────────────────┤
 *  │  TASK LOG  (scrollable, auto-tail, colour-coded by event)    │
 *  └──────────────────────────────────────────────────────────────┘
 *
 *  Updates every 1 second via a Swing Timer (EDT-safe).
 */
public class NodeGUI extends JFrame {

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final Color BG_DARK       = new Color(18, 18, 30);
    private static final Color BG_CARD       = new Color(28, 30, 50);
    private static final Color BG_CARD2      = new Color(35, 37, 60);
    private static final Color ACCENT_BLUE   = new Color(64, 156, 255);
    private static final Color ACCENT_GREEN  = new Color(50, 220, 120);
    private static final Color ACCENT_ORANGE = new Color(255, 165, 50);
    private static final Color ACCENT_RED    = new Color(255, 80, 80);
    private static final Color ACCENT_PURPLE = new Color(180, 120, 255);
    private static final Color TEXT_PRIMARY  = new Color(230, 230, 245);
    private static final Color TEXT_MUTED    = new Color(140, 145, 175);
    private static final Color OFFLINE_GREY  = new Color(80, 80, 100);

    private static final Font FONT_TITLE  = new Font("Segoe UI", Font.BOLD, 22);
    private static final Font FONT_LARGE  = new Font("Segoe UI", Font.BOLD, 18);
    private static final Font FONT_MED    = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font FONT_SMALL  = new Font("Segoe UI", Font.PLAIN, 11);
    private static final Font FONT_MONO   = new Font("Consolas", Font.PLAIN, 11);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ── References ────────────────────────────────────────────────────────────
    private final NodeImpl  node;
    private final String    selfIp;

    // ── Self-stats widgets ────────────────────────────────────────────────────
    private JLabel  lblStatus;
    private JLabel  lblLoad;
    private JLabel  lblActive;
    private JLabel  lblDone;
    private JLabel  lblForwarded;
    private JLabel  lblReceived;
    private LoadBar selfLoadBar;

    // ── Peer widgets (indexed 0,1 for the two peers) ──────────────────────────
    private JPanel[]    peerCards;
    private JLabel[]    peerName;
    private JLabel[]    peerStatus;
    private JLabel[]    peerLoad;
    private JLabel[]    peerActive;
    private JLabel[]    peerDone;
    private LoadBar[]   peerLoadBar;

    // ── Log ───────────────────────────────────────────────────────────────────
    private JTextPane   logPane;
    private StyledDocument logDoc;
    private static final int MAX_LOG_LINES = 500;
    private int logLineCount = 0;

    // ── Constructor ───────────────────────────────────────────────────────────

    public NodeGUI(NodeImpl node, String selfIp) {
        super("Distributed Load Balancer — " + node.getNodeId(false));
        this.node   = node;
        this.selfIp = selfIp;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 760);
        setMinimumSize(new Dimension(900, 640));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);

        buildUI();

        // Wire node log output → GUI log panel
        node.setLogListener(this::appendLog);

        // Start refresh timer (1-second interval, always on EDT)
        new Timer(1000, e -> refreshStats()).start();

        setVisible(true);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        setLayout(new BorderLayout(0, 0));

        add(buildHeader(),   BorderLayout.NORTH);
        add(buildCenter(),   BorderLayout.CENTER);
        add(buildLogPanel(), BorderLayout.SOUTH);
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout(16, 0));
        p.setBackground(new Color(12, 12, 24));
        p.setBorder(new EmptyBorder(14, 20, 14, 20));

        // Left: title
        JLabel title = new JLabel(node.getNodeId(false));
        title.setFont(FONT_TITLE);
        title.setForeground(ACCENT_BLUE);
        p.add(title, BorderLayout.WEST);

        // Centre: subtitle
        JLabel sub = new JLabel("Java RMI Distributed Load Balancer  ·  " + selfIp
                + ":" + ClusterConfig.RMI_PORT);
        sub.setFont(FONT_MED);
        sub.setForeground(TEXT_MUTED);
        p.add(sub, BorderLayout.CENTER);

        // Right: status badge
        lblStatus = badge("● RUNNING", ACCENT_GREEN);
        p.add(lblStatus, BorderLayout.EAST);

        return p;
    }

    // ── Centre section: self card + peer cards ────────────────────────────────

    private JPanel buildCenter() {
        JPanel p = new JPanel(new GridLayout(1, 3, 10, 0));
        p.setBackground(BG_DARK);
        p.setBorder(new EmptyBorder(10, 12, 10, 12));

        p.add(buildSelfCard());
        p.add(buildPeerCards());

        return p;
    }

    private JPanel buildSelfCard() {
        JPanel card = card("THIS NODE  (" + node.getNodeId(false) + ")", ACCENT_BLUE);

        selfLoadBar = new LoadBar();
        card.add(padded(selfLoadBar, 8, 0, 10, 0));

        lblLoad     = statLabel("Load: 0%");
        lblActive   = statLabel("Active tasks: 0 / " + ClusterConfig.MAX_THREADS);
        lblDone     = statLabel("Completed: 0");
        lblForwarded= statLabel("Forwarded out: 0");
        lblReceived = statLabel("Received from peers: 0");

        for (JLabel l : new JLabel[]{lblLoad, lblActive, lblDone, lblForwarded, lblReceived})
            card.add(padded(l, 2, 6, 2, 6));

        card.add(Box.createVerticalGlue());
        return card;
    }

    private JPanel buildPeerCards() {
        // We always have exactly 2 peers
        JPanel outer = new JPanel(new GridLayout(2, 1, 0, 10));
        outer.setBackground(BG_DARK);

        peerCards   = new JPanel[2];
        peerName    = new JLabel[2];
        peerStatus  = new JLabel[2];
        peerLoad    = new JLabel[2];
        peerActive  = new JLabel[2];
        peerDone    = new JLabel[2];
        peerLoadBar = new LoadBar[2];

        String[] allNames = ClusterConfig.allNames();
        int pi = 0;
        for (String name : allNames) {
            if (name.equals(node.getNodeId(false))) continue;
            if (pi >= 2) break;

            JPanel card = card("PEER: " + name, ACCENT_PURPLE);
            peerCards[pi]  = card;
            peerName[pi]   = sectionLabel(name);
            peerStatus[pi] = badge("● CONNECTING…", ACCENT_ORANGE);
            peerLoad[pi]   = statLabel("Load: —");
            peerActive[pi] = statLabel("Active: —");
            peerDone[pi]   = statLabel("Completed: —");
            peerLoadBar[pi]= new LoadBar();

            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            row.setBackground(BG_CARD);
            row.add(peerName[pi]);
            row.add(peerStatus[pi]);
            card.add(row);
            card.add(padded(peerLoadBar[pi], 4, 0, 8, 0));
            for (JLabel l : new JLabel[]{peerLoad[pi], peerActive[pi], peerDone[pi]})
                card.add(padded(l, 2, 6, 2, 6));
            card.add(Box.createVerticalGlue());

            outer.add(card);
            pi++;
        }
        return outer;
    }

    // ── Log panel ─────────────────────────────────────────────────────────────

    private JPanel buildLogPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG_DARK);
        p.setBorder(new CompoundBorder(
                new EmptyBorder(0, 12, 12, 12),
                new LineBorder(new Color(50, 55, 90), 1)));

        // Header bar
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(24, 26, 44));
        header.setBorder(new EmptyBorder(6, 10, 6, 10));
        JLabel title = new JLabel("  TASK LOG");
        title.setFont(FONT_SMALL);
        title.setForeground(TEXT_MUTED);
        header.add(title, BorderLayout.WEST);
        JButton clrBtn = new JButton("Clear");
        clrBtn.setFont(FONT_SMALL);
        clrBtn.setForeground(TEXT_MUTED);
        clrBtn.setBackground(new Color(40, 42, 65));
        clrBtn.setBorderPainted(false);
        clrBtn.setFocusPainted(false);
        clrBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clrBtn.addActionListener(e -> {
            try { logDoc.remove(0, logDoc.getLength()); logLineCount = 0; }
            catch (BadLocationException ignored) {}
        });
        header.add(clrBtn, BorderLayout.EAST);
        p.add(header, BorderLayout.NORTH);

        // Text pane
        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setBackground(new Color(10, 12, 22));
        logPane.setFont(FONT_MONO);
        logDoc = logPane.getStyledDocument();

        // Define named styles
        addStyle("default",   TEXT_PRIMARY,  false);
        addStyle("accept",    ACCENT_GREEN,  false);
        addStyle("done",      new Color(100, 200, 100), false);
        addStyle("offload",   ACCENT_ORANGE, true);
        addStyle("saturated", ACCENT_RED,    true);
        addStyle("gen",       new Color(150, 200, 255), false);
        addStyle("peer",      ACCENT_PURPLE, false);
        addStyle("warn",      ACCENT_RED,    false);
        addStyle("header",    ACCENT_BLUE,   true);

        JScrollPane scroll = new JScrollPane(logPane);
        scroll.setPreferredSize(new Dimension(0, 220));
        scroll.setBackground(BG_DARK);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setBackground(new Color(30, 32, 52));
        p.add(scroll, BorderLayout.CENTER);

        return p;
    }

    // ── Stats refresh (called by Swing Timer every 1 s) ───────────────────────

    private void refreshStats() {
        // Self stats
        try {
            NodeStatus s = node.getLocalStatus();
            double load  = s.getLoad();

            selfLoadBar.setValue(load);
            lblLoad.setText(String.format("Load: %.0f%%  (%d / %d threads)",
                    load * 100, s.getActiveTasks(), s.getMaxThreads()));
            lblLoad.setForeground(loadColour(load));
            lblActive.setText("Active tasks: " + s.getActiveTasks()
                    + " / " + s.getMaxThreads());
            lblDone.setText("Completed: " + s.getCompletedTasks());
            lblForwarded.setText("Forwarded out: " + s.getForwardedTasks());
            lblReceived.setText("Received from peers: " + s.getReceivedTasks());

            boolean overloaded = load >= ClusterConfig.OVERLOAD_THRESHOLD;
            lblStatus.setText(overloaded ? "⚠ OVERLOADED" : "● RUNNING");
            lblStatus.setForeground(overloaded ? ACCENT_RED : ACCENT_GREEN);

        } catch (Exception e) {
            lblStatus.setText("⛔ ERROR");
            lblStatus.setForeground(ACCENT_RED);
        }

        // Peer stats
        List<NodeStatus> peerStatuses = node.getPeerStatuses();
        for (int i = 0; i < Math.min(2, peerStatuses.size()); i++) {
            NodeStatus ps = peerStatuses.get(i);
            boolean online = ps.isOnline();

            peerName[i].setText(ps.getNodeId());
            peerStatus[i].setText(online ? "● ONLINE" : "● OFFLINE");
            peerStatus[i].setForeground(online ? ACCENT_GREEN : ACCENT_RED);

            peerCards[i].setBackground(online ? BG_CARD : new Color(28, 20, 25));

            if (online) {
                double pl = ps.getLoad();
                peerLoadBar[i].setValue(pl);
                peerLoad[i].setText(String.format("Load: %.0f%%", pl * 100));
                peerLoad[i].setForeground(loadColour(pl));
                peerActive[i].setText("Active: " + ps.getActiveTasks()
                        + "/" + ps.getMaxThreads());
                peerDone[i].setText("Completed: " + ps.getCompletedTasks());
            } else {
                peerLoadBar[i].setValue(0);
                peerLoad[i].setText("Load: —");
                peerLoad[i].setForeground(OFFLINE_GREY);
                peerActive[i].setText("Active: —");
                peerDone[i].setText("Completed: —");
            }
        }
    }

    // ── Log appender (called from any thread, marshalled to EDT) ─────────────

    public void appendLog(String line) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Trim old lines to keep memory bounded
                if (logLineCount >= MAX_LOG_LINES) {
                    Element root = logDoc.getDefaultRootElement();
                    Element first = root.getElement(0);
                    int end = first.getEndOffset();
                    logDoc.remove(0, end);
                    logLineCount--;
                }

                String style = styleFor(line);
                logDoc.insertString(logDoc.getLength(),
                        line + "\n",
                        logDoc.getStyle(style));
                logLineCount++;

                // Auto-scroll to bottom
                logPane.setCaretPosition(logDoc.getLength());

            } catch (BadLocationException ignored) {}
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String styleFor(String line) {
        String u = line.toUpperCase();
        if (u.contains("OFFLOAD"))   return "offload";
        if (u.contains("SATURATED")) return "saturated";
        if (u.contains("ACCEPT"))    return "accept";
        if (u.contains("DONE"))      return "done";
        if (u.contains("GEN-SEND") || u.contains("GEN-RESULT")) return "gen";
        if (u.contains("PEER"))      return "peer";
        if (u.contains("WARN") || u.contains("ERR")) return "warn";
        if (u.contains("STARTED") || u.contains("STOPPED")) return "header";
        return "default";
    }

    private void addStyle(String name, Color fg, boolean bold) {
        Style s = logPane.addStyle(name, null);
        StyleConstants.setForeground(s, fg);
        StyleConstants.setBold(s, bold);
    }

    private static Color loadColour(double load) {
        if (load >= 0.85) return ACCENT_RED;
        if (load >= 0.60) return ACCENT_ORANGE;
        return ACCENT_GREEN;
    }

    // ── Card builder ──────────────────────────────────────────────────────────

    private static JPanel card(String title, Color accent) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(BG_CARD);
        p.setBorder(new CompoundBorder(
                new LineBorder(accent.darker().darker(), 1),
                new EmptyBorder(10, 12, 10, 12)));

        JLabel lbl = sectionLabel(title);
        lbl.setForeground(accent);
        p.add(lbl);
        p.add(Box.createVerticalStrut(6));
        return p;
    }

    private static JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FONT_LARGE);
        l.setForeground(TEXT_PRIMARY);
        return l;
    }

    private static JLabel statLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FONT_MED);
        l.setForeground(TEXT_PRIMARY);
        return l;
    }

    private static JLabel badge(String text, Color fg) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.BOLD, 13));
        l.setForeground(fg);
        return l;
    }

    private static JPanel padded(Component c, int top, int left, int bot, int right) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG_CARD);
        p.setBorder(new EmptyBorder(top, left, bot, right));
        p.add(c);
        return p;
    }

    // ── Inner class: animated load bar ────────────────────────────────────────

    static class LoadBar extends JPanel {
        private double value = 0.0;

        LoadBar() {
            setPreferredSize(new Dimension(0, 20));
            setBackground(new Color(20, 22, 40));
        }

        void setValue(double v) {
            this.value = Math.max(0, Math.min(1, v));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int filled = (int)(value * w);

            // Background track
            g2.setColor(new Color(30, 32, 55));
            g2.fillRoundRect(0, 0, w, h, 8, 8);

            // Filled portion — colour shifts green→orange→red
            Color barCol;
            if (value >= 0.85)      barCol = ACCENT_RED;
            else if (value >= 0.60) barCol = ACCENT_ORANGE;
            else                    barCol = ACCENT_GREEN;

            if (filled > 0) {
                g2.setColor(barCol);
                g2.fillRoundRect(0, 0, filled, h, 8, 8);

                // Subtle shine
                g2.setColor(new Color(255, 255, 255, 30));
                g2.fillRoundRect(0, 0, filled, h / 2, 8, 8);
            }

            // Percentage text
            g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
            g2.setColor(Color.WHITE);
            String pct = String.format("%.0f%%", value * 100);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(pct,
                    (w - fm.stringWidth(pct)) / 2,
                    (h + fm.getAscent() - fm.getDescent()) / 2);
        }
    }
}

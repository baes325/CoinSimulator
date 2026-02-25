package com.team.coin_simulator.profile;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.io.File;

public class CircleImageLabel extends JLabel {
    private final int size;
    private Image image;

    public CircleImageLabel(int size) {
        this.size = size;
        setPreferredSize(new Dimension(size, size));
        setMaximumSize(new Dimension(size, size));
        setMinimumSize(new Dimension(size, size));
    }

    public void setImagePath(String path) {
        if (path == null || path.isBlank()) {
            image = null;
            System.out.println("[CircleImageLabel] path is null/blank");
            revalidate();
            repaint();
            return;
        }

        try {
            java.io.File f = new java.io.File(path);
            System.out.println("[CircleImageLabel] load path=" + path + " exists=" + f.exists() + " size=" + (f.exists() ? f.length() : -1));

            java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(f);
            if (bi == null) {
                System.out.println("[CircleImageLabel] ImageIO.read returned null (unsupported format or read fail)");
                image = null;
            } else {
                image = bi.getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH);
                System.out.println("[CircleImageLabel] image loaded OK");
            }
        } catch (Exception e) {
            System.out.println("[CircleImageLabel] load failed: " + e.getMessage());
            e.printStackTrace();
            image = null;
        }

        revalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2.setColor(new Color(240, 242, 245));
        g2.fillOval(0, 0, size, size);

        Shape clip = new Ellipse2D.Float(0, 0, size, size);
        g2.setClip(clip);

        if (image != null) {
            g2.drawImage(image, 0, 0, size, size, this);
        } else {
            g2.setClip(null);
            g2.setColor(new Color(160, 160, 160));
            g2.setFont(new Font("맑은 고딕", Font.BOLD, 14));
            FontMetrics fm = g2.getFontMetrics();
            String text = "NO IMAGE";
            int x = (size - fm.stringWidth(text)) / 2;
            int y = (size + fm.getAscent()) / 2 - 2;
            g2.drawString(text, x, y);
        }

        g2.setClip(null);
        g2.setColor(new Color(220, 220, 220));
        g2.setStroke(new BasicStroke(2f));
        g2.drawOval(1, 1, size - 2, size - 2);

        g2.dispose();
    }
}
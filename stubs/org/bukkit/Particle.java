package org.bukkit;

public enum Particle {
    REDSTONE,
    DUST;

    public static class DustOptions {
        private final Color color;
        private final float size;

        public DustOptions(Color color, float size) {
            this.color = color;
            this.size = size;
        }

        public Color getColor() {
            return color;
        }

        public float getSize() {
            return size;
        }
    }
}


package dev.ameruzily.campsystem.models;

public class CampBoundary {
    private double west;
    private double east;
    private double north;
    private double south;

    public CampBoundary(double radius) {
        this(radius, radius, radius, radius);
    }

    public CampBoundary(double west, double east, double north, double south) {
        this.west = Math.max(0.0, west);
        this.east = Math.max(0.0, east);
        this.north = Math.max(0.0, north);
        this.south = Math.max(0.0, south);
    }

    public double west() { return west; }

    public double east() { return east; }

    public double north() { return north; }

    public double south() { return south; }

    public double maxRadius() {
        return Math.max(Math.max(west, east), Math.max(north, south));
    }

    public CampBoundary copy() {
        return new CampBoundary(west, east, north, south);
    }

    public void setWest(double west) { this.west = Math.max(0.0, west); }

    public void setEast(double east) { this.east = Math.max(0.0, east); }

    public void setNorth(double north) { this.north = Math.max(0.0, north); }

    public void setSouth(double south) { this.south = Math.max(0.0, south); }
}

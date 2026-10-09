package io.github.cloraj1.jukebox;

/** Same bounded-volume/layout math on the Java 17, 21 and 25 game runtimes. */
final class Maths {
    private Maths() {}
    static int clamp(int value,int min,int max) {
        if(min>max) throw new IllegalArgumentException("Invalid bounds");
        return Math.max(min,Math.min(max,value));
    }
    static long clamp(long value,long min,long max) {
        if(min>max) throw new IllegalArgumentException("Invalid bounds");
        return Math.max(min,Math.min(max,value));
    }
    static float clamp(float value,float min,float max) {
        if(min>max) throw new IllegalArgumentException("Invalid bounds");
        return Math.max(min,Math.min(max,value));
    }
    static double clamp(double value,double min,double max) {
        if(min>max) throw new IllegalArgumentException("Invalid bounds");
        return Math.max(min,Math.min(max,value));
    }
}

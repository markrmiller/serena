package demo;

public class FieldSample {
    public int count = 1;
    public boolean ready = true;

    int read() {
        return count;
    }

    void write(int value) {
        count = value;
    }

    boolean check() {
        return ready;
    }
}

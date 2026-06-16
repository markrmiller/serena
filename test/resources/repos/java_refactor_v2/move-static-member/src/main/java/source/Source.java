package source;

import target.Target;

public class Source {
    public static int count = 1;

    public static int value() {
        return count;
    }

    void run() {
        int v = value();
    }
}

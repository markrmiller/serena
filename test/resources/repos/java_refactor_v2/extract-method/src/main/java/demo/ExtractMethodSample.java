package demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ExtractMethodSample {
    private int field = 2;

    public void run(String prefix) throws IOException {
        int base = 1;
        int total = base + field;
        System.out.println(prefix + total);
        Files.readString(Path.of("demo.txt"));
        System.out.println(total);
    }

    public static int compute(int left, int right) {
        int sum = left + right;
        return sum * 2;
    }
}

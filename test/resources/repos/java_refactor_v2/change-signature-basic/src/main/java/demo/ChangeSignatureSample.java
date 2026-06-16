package demo;

public class ChangeSignatureSample {
    public String greet(String name) {
        return "Hello, " + name;
    }

    void run() {
        greet("Serena");
        greet("Remote");
    }
}

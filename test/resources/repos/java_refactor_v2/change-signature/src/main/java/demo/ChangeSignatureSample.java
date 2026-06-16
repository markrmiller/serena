package demo;

public class ChangeSignatureSample {
    public String greet(String name) {
        return "Hello " + name + "Hello ";
    }

    public String caller() {
        return greet("Serena");
    }
}

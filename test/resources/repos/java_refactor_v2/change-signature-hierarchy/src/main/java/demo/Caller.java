package demo;

public class Caller {
    void run() {
        Base b = new Base();
        b.describe("x");
        Child c = new Child();
        c.describe("y");
    }
}

package demo;

public class Child extends Base {
    @Override
    public String describe(String name) {
        return "child: " + name;
    }
}

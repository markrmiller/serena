package demo;

public class OtherCaller {
    public String call() {
        return new ChangeSignatureSample().greet("Remote");
    }
}

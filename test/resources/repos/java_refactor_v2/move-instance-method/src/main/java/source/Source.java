package source;

import target.Target;

public class Source {
    public String format(Target target) {
        return "source: " + target.name();
    }

    void run() {
        Target t = new Target();
        String result = format(t);
    }
}

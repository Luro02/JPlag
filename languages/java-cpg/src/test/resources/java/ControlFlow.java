class ControlFlow {
    void doWhileLoop(int remaining) {
        do {
            System.out.println(remaining--);
        } while (remaining > 0);
        System.out.println("after");
    }

    void synchronizedBlock(Object lock) {
        synchronized (lock) {
            System.out.println("body");
        }
        System.out.println("after");
    }

    int switchReturns(int value) {
        switch (value) {
            case 0:
                return 1;
            default:
                return 2;
        }
    }

    void nestedCalls(String text) {
        System.out.println(new StringBuilder(text.trim()).append(text.strip()).toString());
    }

    void conditionWithLambda() {
        if (java.util.stream.Stream.of(1).anyMatch(value -> {
            System.out.println(value);
            return true;
        })) {
            System.out.println("then");
        }
        {
            System.out.println("after");
        }
    }

    void switchFallthrough(int value) {
        switch (value) {
            case 0:
            case 1:
                System.out.println("first");
            case 2:
                System.out.println("second");
                break;
            default:
                System.out.println("default");
        }
        System.out.println("after");
    }

    void emptySynchronized(Object lock) {
        synchronized (lock) {
        }
    }

    int switchDependencies(int input) {
        int selector = input + 1;
        int bodyValue = input * 2;
        int result = 0;
        switch (selector) {
            case 0:
                result = bodyValue;
                break;
            default:
                result = selector;
        }
        int copy = result;
        return copy;
    }

    Object synchronizedDependencies(Object input) {
        Object lock = input;
        Object bodyValue = input;
        Object result = null;
        synchronized (lock) {
            result = bodyValue;
        }
        Object copy = result;
        return copy;
    }

    void nestedControlFlow(Object lock, int remaining) {
        while (remaining-- > 0) {
            synchronized (lock) {
                switch (remaining) {
                    case 0:
                        System.out.println("zero");
                        break;
                    default:
                        System.out.println("other");
                }
            }
        }
    }
}

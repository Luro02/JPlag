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
}

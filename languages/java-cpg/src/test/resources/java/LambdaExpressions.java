class LambdaExpressions {
    Runnable block() {
        return () -> {
            System.out.println("body");
        };
    }

    java.util.function.IntUnaryOperator expression() {
        return value -> value + 1;
    }

    java.util.function.IntBinaryOperator multiline() {
        return (left, right) ->
            left + right;
    }
}

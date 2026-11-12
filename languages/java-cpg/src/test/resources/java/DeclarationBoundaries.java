class DeclarationBoundaries {
    void run() {
        System.out.println("run");
    }

    String value() {
        return "value";
    }

    // This comment must not belong to the closing token.
}

class EmptyDeclaration {}

enum EmptyEnum {}

enum PopulatedEnum {
    FIRST, SECOND;

    // This comment must not belong to the closing token.
}

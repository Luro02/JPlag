class EnhancedFor {
    void braced(String[] values) {
        for (String value : values) {
            System.out.println(value);
        }
    }

    void unbraced(String[] values) {
        for (String value : values)
            System.out.println(value);
        {
            System.out.println("after");
        }
    }

    void nested(String[][] values) {
        for (String[] row : values)
            for (String value : row)
                System.out.println(value);
    }
}

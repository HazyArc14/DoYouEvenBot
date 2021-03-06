package com.hazyarc14.enums;

public enum RANK {

    GUARDIAN("Guardian", 0.0),
    BRAVE("Brave",30.0),
    HEROIC("Heroic",150.0),
    FABLED("Fabled",300.0),
    MYTHIC("Mythic",1000.0),
    LEGEND("Legend",2500.0),
    MAX("MAX", 9999.9);

    private String roleName;
    private double value;

    RANK(String roleName, double value) {
        this.roleName = roleName;
        this.value = value;
    }

    public String getRoleName() {
        return roleName;
    }

    public double getValue() {
        return value;
    }

    private static RANK[] values = values();

    public RANK previous() {
        return values[(this.ordinal() - 1  + values.length) % values.length];
    }

    public RANK next() {
        return values[(this.ordinal() + 1) % values.length];
    }

}
package me.meiser79.intercomsatellite;

/**
 * The symbols a kiosk can be given, stored by name. Versions before 2.0.3 stored a character
 * instead; those map onto the nearest symbol.
 */
final class RoomIcons {
    static final String[] NAMES = {
        "home", "living", "kitchen", "dining", "bedroom", "kids",
        "office", "bath", "entrance", "garage", "garden", "stairs"
    };
    private static final int[] DRAWABLES = {
        R.drawable.ic_room_home,
        R.drawable.ic_room_living,
        R.drawable.ic_room_kitchen,
        R.drawable.ic_room_dining,
        R.drawable.ic_room_bedroom,
        R.drawable.ic_room_kids,
        R.drawable.ic_room_office,
        R.drawable.ic_room_bath,
        R.drawable.ic_room_entrance,
        R.drawable.ic_room_garage,
        R.drawable.ic_room_garden,
        R.drawable.ic_room_stairs
    };
    private static final int[] LABELS = {
        R.string.room_icon_home,
        R.string.room_icon_living,
        R.string.room_icon_kitchen,
        R.string.room_icon_dining,
        R.string.room_icon_bedroom,
        R.string.room_icon_kids,
        R.string.room_icon_office,
        R.string.room_icon_bath,
        R.string.room_icon_entrance,
        R.string.room_icon_garage,
        R.string.room_icon_garden,
        R.string.room_icon_stairs
    };

    private RoomIcons() {}

    /** The symbol's name for a stored value, old characters included; unknown ones are home. */
    static String name(String stored) {
        if (stored == null) return NAMES[0];
        switch (stored) {
            case "♪":
            case "✦":
                return "living";
            case "☼":
                return "garden";
            default:
                for (String n : NAMES) if (n.equals(stored)) return n;
                return NAMES[0];
        }
    }

    static int drawable(String stored) {
        return DRAWABLES[index(stored)];
    }

    static int label(String stored) {
        return LABELS[index(stored)];
    }

    private static int index(String stored) {
        String n = name(stored);
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(n)) return i;
        return 0;
    }
}

TICKS_PER_HOUR = 60
TICKS_PER_DAY = 1440


def day(tick):
    return tick // TICKS_PER_DAY


def hour(tick):
    return (tick % TICKS_PER_DAY) // TICKS_PER_HOUR


def phase(tick):
    h = hour(tick)
    if 5 <= h <= 7:
        return "DAWN"
    if 8 <= h <= 11:
        return "MORNING"
    if 12 <= h <= 14:
        return "MIDDAY"
    if 15 <= h <= 18:
        return "AFTERNOON"
    if 19 <= h <= 21:
        return "DUSK"
    return "NIGHT"

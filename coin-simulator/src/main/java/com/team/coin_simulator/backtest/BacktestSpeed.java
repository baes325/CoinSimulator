package com.team.coin_simulator.backtest;

/**
 * 백테스팅 배속 옵션
 * - 실제 1초가 경과할 때마다 시뮬레이션 시간을 얼마나 앞당길지 정의합니다.
 */
public enum BacktestSpeed {

    SPEED_1X  ("1배속",  "1초 = 1분",   1),
    SPEED_10X ("10배속", "1초 = 10분",  10),
    SPEED_30X ("30배속", "1초 = 30분",  30),
    SPEED_60X ("60배속", "1초 = 1시간", 60);

    private final String label;
    private final String description;
    /** 실제 1초 경과 시 시뮬레이션에서 진행되는 분(minute) 수 */
    private final int minutesPerTick;

    BacktestSpeed(String label, String description, int minutesPerTick) {
        this.label          = label;
        this.description    = description;
        this.minutesPerTick = minutesPerTick;
    }

    public String  getLabel()          { return label; }
    public String  getDescription()    { return description; }
    public int     getMinutesPerTick() { return minutesPerTick; }

    /** 종료 1주일 전 구간에서 허용되는 배속인지 확인합니다. */
    public boolean isAllowedInFinalWeek() {
        return this == SPEED_1X || this == SPEED_10X;
    }

    @Override
    public String toString() { return label + " (" + description + ")"; }
}
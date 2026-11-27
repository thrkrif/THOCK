package com.thock.back.market.config;

// TODO(debug): 주문 생성 API 한정 커넥션 획득 시점 확인용 임시 마커. 확인 끝나면 이 클래스와 사용처 모두 제거.
public final class OrderCreationConnectionDebugMarker {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private OrderCreationConnectionDebugMarker() {
    }

    public static void mark() {
        ACTIVE.set(true);
    }

    public static void clear() {
        ACTIVE.remove();
    }

    public static boolean isActive() {
        return ACTIVE.get();
    }
}

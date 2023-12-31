package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

public class SystemConstants {
    public static final String IMAGE_UPLOAD_DIR = "D:\\nginx-1.18.0\\html\\hmdp\\imgs";
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
    public static final String TOKEN_PREFIX = "token:";
    public static final String PHONE_PREFIX = "phone_code:";
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop_type";
}

package cn.powerr.mamabike.cache;

import cn.powerr.mamabike.common.exception.MaMaBikeException;
import cn.powerr.mamabike.user.entity.UserElement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

import java.util.Map;

@Component
@Slf4j
public class CommonCacheUtil {
    @Autowired
    private JedisPoolWrapper jedisPoolWrapper;

    private static final String TOKEN_PREFIX = "token.";

    private static final String USER_PREFIX = "user.";

    /**
     * 缓存 存value永久
     *
     * @param key
     * @param value
     */
    public void cache(String key, String value) {
        try {
            JedisPool pool = jedisPoolWrapper.getJedisPool();
            if (pool != null) {
                try (Jedis jedis = pool.getResource()) {
                    jedis.select(0);
                    jedis.set(key, value);
                }
            }
        } catch (Exception e) {
            log.error("fail to cache value", e);
        }
    }

    /**
     * 获取缓存的value
     *
     * @param key
     * @return
     */
    public String getCache(String key) {
        String value = null;
        try {
            JedisPool pool = jedisPoolWrapper.getJedisPool();
            if (pool != null) {
                try (Jedis jedis = pool.getResource()) {
                    jedis.select(0);
                    value = jedis.get(key);
                }
            }
        } catch (Exception e) {
            log.error("fail to get cached value", e);
        }
        return value;
    }

    /**
     * 设置key,value以及过期时间
     *
     * @param key
     * @param value
     * @param expiry
     * @return
     */
    public long cacheNxExpire(String key, String value, int expiry) {
        long result = 0;
        try {
            JedisPool pool = jedisPoolWrapper.getJedisPool();
            if (pool != null) {
                try (Jedis jedis = pool.getResource()) {
                    jedis.select(0);
                    result = jedis.setnx(key, value);
                    jedis.expire(key, expiry);
                }
            }
        } catch (Exception e) {
            log.error("fail to cacheNx value", e);
        }
        return result;
    }

    /**
     * 删除缓存key
     *
     * @param key
     */
    public void delKey(String key) {
        JedisPool pool = jedisPoolWrapper.getJedisPool();
        if (pool != null) {
            try (Jedis jedis = pool.getResource()) {
                jedis.select(0);
                try {
                    jedis.del(key);
                } catch (Exception e) {
                    log.error("fail to remove key from redis", e);
                }
            }
        }
    }

    /**
     * 登录时设置token
     *
     * @param ue
     */
    public void putTokenWhenLogin(UserElement ue) {
        JedisPool pool = jedisPoolWrapper.getJedisPool();
        if (pool != null) {
            try (Jedis jedis = pool.getResource()) {
                jedis.select(0);
                Transaction trans = jedis.multi();
                try {
                    trans.del(TOKEN_PREFIX + ue.getToken());
                    trans.hmset(TOKEN_PREFIX + ue.getToken(), ue.toMap());
                    trans.expire(TOKEN_PREFIX + ue.getToken(), 2592000);
                    //to avoid multi-platform
                    trans.sadd(USER_PREFIX + ue.getUserId(), ue.getToken());
                    trans.exec();
                } catch (Exception e) {
                    trans.discard();
                    log.error("fail to cache token to redis", e);
                }
            }
        }
    }

    public UserElement getUserByToken(String token) {
        UserElement ue = new UserElement();
        JedisPool pool = jedisPoolWrapper.getJedisPool();
        if (pool != null) {
            try (Jedis jedis = pool.getResource()) {
                jedis.select(0);
                try {
                    Map<String, String> map = jedis.hgetAll(TOKEN_PREFIX + token);
                    if (!CollectionUtils.isEmpty(map)) {
                        ue = UserElement.fromMap(map);
                    } else {
                        log.warn("fail to find cached element  for token");
                    }
                } catch (Exception e) {
                    log.error("Fail to get user by token in redis", e);
                    throw e;
                }
            }
        }
        return ue;
    }

    /**
     * 缓存手机验证码用 限制发送次数
     *
     * @param key 手机号
     * @return 1 当前手机验证码未过期, 2手机号超过当日发送次数, 3 ip超过当日验证码次数上线
     */
    public int cacheForVerificationCode(String key, String verCode, String type, int second, String ip) throws MaMaBikeException {
        JedisPool pool = jedisPoolWrapper.getJedisPool();
        if (pool != null) {
            try (Jedis jedis = pool.getResource()) {
                jedis.select(0);
                String ipKey = "ip." + ip;
                if (ip == null) {
                    return 3;
                } else {
                    // 当前ip发送次数
                    String ipSendCount = jedis.get(ipKey);
                    try {
                        if (ipSendCount != null && Integer.parseInt(ipSendCount) >= 10) {
                            return 3;
                        }
                    } catch (NumberFormatException e) {
                        log.error("Fail to process ip send count", e);
                        return 3;
                    }
                }
                //返回1表示redis设置成功，返回0表示redis存在对应的key
                long succ = jedis.setnx(key, verCode);
                if (succ == 0) {
                    return 1;
                }

                String sendCount = jedis.get(key + "." + type);
                try {
                    if (sendCount != null && Integer.parseInt(sendCount) >= 10) {
                        jedis.del(key);
                        return 2;
                    }
                } catch (NumberFormatException e) {
                    log.error("Fail to process send count", e);
                    jedis.del(key);
                    return 2;
                }

                try {
                    // trans.set(key, value);
                    jedis.expire(key, second);
                    long val = jedis.incr(key + "." + type);
                    //自增后是1表明是第一次发验证码
                    if (val == 1) {
                        jedis.expire(key + "." + type, 86400);
                    }

                    jedis.incr(ipKey);
                    if (val == 1) {
                        jedis.expire(ipKey, 86400);
                    }
                } catch (Exception e) {
                    log.error("Fail to cache data into redis", e);
                }
            }
        }
        //验证码发送成功
        return 0;
    }
}

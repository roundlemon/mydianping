---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by limengyuan.
--- DateTime: 2023/8/12 22:59
---


if (redis.call('get', KEYS[1]) == ARGV[1]) then
    redis.call('del', KEYS[1])
end
return 0;
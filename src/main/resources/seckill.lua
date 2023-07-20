---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by marui.
--- DateTime: 2023/7/19 23:28
---
local voucherId = ARGV[1]
local userId = ARGV[2]

-- 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 订单key
local orderKey = 'seckill:order:' .. voucherId

--脚本业务
--判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    --库存不足 return 1
    return 1;
end
--判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    --用户已经下过单
    return 2;
end
--扣库存
redis.call('incrby', stockKey, -1)
--下单(保存用户)
redis.call('sadd', orderKey, userId)
return 0
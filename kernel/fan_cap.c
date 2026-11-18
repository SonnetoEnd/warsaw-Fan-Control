/* SPDX-License-Identifier: GPL-2.0-only */
#include "warsaw_abi.h"

#define MODULE_NAME "k90_fan_cap"
#define ENODEV 19
#define EIO 5
#define ERANGE 34
#define READ_ONCE(field) (*(const volatile __typeof__(field) *)&(field))
#define WRITE_ONCE(field, value) (*(volatile __typeof__(field) *)&(field) = (value))

static const struct fan_gear stock_gears[FAN_LEVELS] = {
    { 0, 0 }, { 51, 12000 }, { 65, 14000 }, { 73, 16000 }, { 98, 19800 },
};

static const uint8_t driver_build_id[20] = {
    0xad, 0x6d, 0x52, 0xad, 0xcc, 0xc7, 0x81, 0xf8, 0x1b, 0x58,
    0xbf, 0x99, 0xfc, 0x3d, 0x31, 0x8b, 0xea, 0x24, 0xef, 0x69,
};

static unsigned int limit_rpm;

static struct fan_gear ceiling_gear(unsigned int limit)
{
    if (!limit)
        return stock_gears[FAN_LEVELS - 1];

    for (unsigned int level = 2; level < FAN_LEVELS; level++) {
        struct fan_gear lower = stock_gears[level - 1];
        struct fan_gear upper = stock_gears[level];

        if (limit <= upper.rpm) {
            unsigned int duty = lower.duty +
                (limit - lower.rpm) * (upper.duty - lower.duty) / (upper.rpm - lower.rpm);
            return (struct fan_gear){ duty, limit };
        }
    }
    return stock_gears[FAN_LEVELS - 1];
}

static bool is_expected_driver(const struct device_driver *driver)
{
    if (!driver || !driver->owner)
        return false;
    for (size_t i = 0; i < sizeof(driver_build_id); i++) {
        if (driver->owner->build_id[i] != driver_build_id[i])
            return false;
    }
    return true;
}

static bool expected_gears(const struct fan_context *fan, unsigned int limit)
{
    struct fan_gear ceiling = ceiling_gear(limit);

    for (unsigned int level = 0; level < FAN_LEVELS; level++) {
        struct fan_gear expected = stock_gears[level];
        if (limit && expected.rpm > limit)
            expected = ceiling;
        if (fan->gears[level].rpm != expected.rpm || fan->gears[level].duty != expected.duty)
            return false;
    }
    return true;
}

/* The driver uses 100 to resume the latest normal request after a factory test. */
static unsigned int active_level(const struct fan_context *fan)
{
    unsigned int debug = READ_ONCE(fan->debug_level);
    unsigned int level = debug == 100 ? READ_ONCE(fan->requested_level) : debug - 100;

    return level < FAN_LEVELS ? level : 0;
}

static int retune_running_fan(struct fan_context *fan, unsigned int level, struct fan_gear gear)
{
    unsigned int old_target = READ_ONCE(fan->target_rpm);
    unsigned int old_duty = READ_ONCE(fan->duty);

    if (level == 0 || gear.rpm == old_target || !fan->powered || !old_duty)
        return 0;
    if (!fan->pwm_state.enabled || old_duty > 100)
        return -EIO;

    /* Start near the new target. The driver's workqueue then uses its tachometer. */
    unsigned int duty = gear.duty;
    if ((gear.rpm < old_target && duty > old_duty) || (gear.rpm > old_target && duty < old_duty))
        duty = old_duty;
    if (duty == old_duty || active_level(fan) != level)
        return 0;

    struct pwm_state state = fan->pwm_state;
    state.duty_cycle = state.period * duty / 100;
    int error = pwm_apply_state(fan->pwm, &state);
    if (!error) {
        fan->pwm_state.duty_cycle = state.duty_cycle;
        WRITE_ONCE(fan->duty, duty);
    }
    return error;
}

/* device->mutex keeps the driver bound and fan->lock serializes PWM changes. */
static int configure_fan(unsigned int limit, bool inspect, bool exiting, unsigned int *target)
{
    struct device *device = bus_find_device(&platform_bus_type, NULL, "soc:xiaomi_fan", device_match_name);
    int error = -ENODEV;

    if (!device)
        return error;
    mutex_lock(&device->mutex);
    if (!is_expected_driver(device->driver) || !device->driver_data)
        goto unlock_device;

    struct fan_context *fan = device->driver_data;
    mutex_lock(&fan->lock);
    /* The stock driver rounds 10^9 / 37500 to the nearest nanosecond. */
    if (!fan->supported || !fan->pwm || fan->pwm_state.period != 26667 ||
        !expected_gears(fan, READ_ONCE(limit_rpm)))
        goto unlock_fan;

    error = 0;
    if (!inspect && limit != READ_ONCE(limit_rpm)) {
        struct fan_gear ceiling = ceiling_gear(limit);
        unsigned int level = active_level(fan);
        struct fan_gear gear = stock_gears[level];
        if (limit && gear.rpm > limit)
            gear = ceiling;
        error = retune_running_fan(fan, level, gear);
        if (error && !exiting)
            goto unlock_fan;

        for (unsigned int i = 1; i < FAN_LEVELS; i++) {
            gear = stock_gears[i];
            if (limit && gear.rpm > limit)
                gear = ceiling;
            WRITE_ONCE(fan->gears[i].duty, gear.duty);
            WRITE_ONCE(fan->gears[i].rpm, gear.rpm);
        }

        level = active_level(fan);
        unsigned int rpm = READ_ONCE(fan->gears[level].rpm);
        if (rpm != READ_ONCE(fan->target_rpm)) {
            WRITE_ONCE(fan->target_rpm, rpm);
            WRITE_ONCE(fan->adjust_count, 0);
            /* This kernel's unbound queue uses CPU 32 and a 250 Hz clock. */
            if (fan->powered && rpm)
                queue_delayed_work_on(32, system_wq, &fan->adjust_work, 125);
        }
        WRITE_ONCE(limit_rpm, limit);
    }
    if (target)
        *target = READ_ONCE(fan->target_rpm);

unlock_fan:
    mutex_unlock(&fan->lock);
unlock_device:
    mutex_unlock(&device->mutex);
    put_device(device);
    return error;
}

static int set_ceiling(const char *value, const struct kernel_param *parameter)
{
    int rpm;
    int error = kstrtoint(value, 10, &rpm);

    if (error)
        return error;
    if (rpm != 0 && (rpm < 12000 || rpm > 19800))
        return -ERANGE;
    return configure_fan(rpm, false, false, NULL);
}

static int get_ceiling(char *buffer, const struct kernel_param *parameter)
{
    return scnprintf(buffer, 4096, "%u\n", READ_ONCE(limit_rpm));
}

static int get_target(char *buffer, const struct kernel_param *parameter)
{
    unsigned int target;
    int error = configure_fan(0, true, false, &target);

    return error ? error : scnprintf(buffer, 4096, "%u\n", target);
}

int init_module(void)
{
    return configure_fan(0, true, false, NULL);
}

void cleanup_module(void)
{
    configure_fan(0, false, true, NULL);
}

__attribute__((section(".gnu.linkonce.this_module"), aligned(64), used))
struct module __this_module = { .name = MODULE_NAME, .init = init_module, .exit = cleanup_module };

static const struct kernel_param_ops ceiling_ops = { .set = set_ceiling, .get = get_ceiling };
static const struct kernel_param_ops target_ops = { .get = get_target };

__attribute__((section("__param"), used))
static const struct kernel_param parameters[] = {
    { .name = "ceiling_rpm", .module = &__this_module, .ops = &ceiling_ops, .perm = 0600, .level = -1 },
    { .name = "target_rpm", .module = &__this_module, .ops = &target_ops, .perm = 0400, .level = -1 },
};

struct version_record {
    uint64_t crc;
    char name[56];
};

/* CRCs matched against loaded vendor modules on the supported kernel. */
__attribute__((section("__versions"), used))
static const struct version_record versions[] = {
    { 0x4e276f37, "module_layout" },
    { 0x0740a594, "platform_bus_type" },
    { 0x2794dcc7, "device_match_name" },
    { 0x366d1cdb, "bus_find_device" },
    { 0x753efb34, "put_device" },
    { 0xd5977bfb, "mutex_lock" },
    { 0xed55cabd, "mutex_unlock" },
    { 0xef40c187, "pwm_apply_state" },
    { 0xbf57e89e, "queue_delayed_work_on" },
    { 0x2d3385d3, "system_wq" },
    { 0x8c8569cb, "kstrtoint" },
    { 0x96848186, "scnprintf" },
};

#define MODULE_INFO(key, value) \
    __attribute__((section(".modinfo"), used)) static const char info_##key[] = #key "=" value
MODULE_INFO(name, MODULE_NAME);
MODULE_INFO(license, "GPL");
MODULE_INFO(description, "Adjust the K90 Ultra fan driver's RPM ceiling");
MODULE_INFO(depends, "");
MODULE_INFO(vermagic, "6.6.142-android15-8-4k-Haruhi-26w37f SMP preempt mod_unload modversions aarch64");

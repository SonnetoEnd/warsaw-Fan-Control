/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef WARSAW_ABI_H
#define WARSAW_ABI_H

/*
 * Layouts from the tested Haruhi kernel's BTF and the Warsaw mca_pwm_fan
 * binary. The module loader script checks both builds before loading this file.
 */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct bus_type;
struct pwm_device;
struct workqueue_struct;

struct mutex {
    uint64_t storage[6];
};

struct delayed_work {
    uint64_t storage[17];
};

struct module {
    uint8_t before_name[0x18];
    char name[56];
    uint8_t build_id[20];
    uint8_t before_init[0x188 - 0x64];
    int (*init)(void);
    uint8_t before_exit[0x5b8 - 0x190];
    void (*exit)(void);
    uint8_t after_exit[0x600 - 0x5c0];
};

struct device_driver {
    const char *name;
    const struct bus_type *bus;
    struct module *owner;
};

struct device {
    uint8_t before_driver[0x88];
    struct device_driver *driver;
    void *platform_data;
    void *driver_data;
    struct mutex mutex;
};

struct kernel_param;

struct kernel_param_ops {
    unsigned int flags;
    int (*set)(const char *value, const struct kernel_param *parameter);
    int (*get)(char *buffer, const struct kernel_param *parameter);
    void (*free)(void *);
};

struct kernel_param {
    const char *name;
    struct module *module;
    const struct kernel_param_ops *ops;
    uint16_t perm;
    int8_t level;
    uint8_t flags;
    void *arg;
};

struct pwm_state {
    uint64_t period;
    uint64_t duty_cycle;
    unsigned int polarity;
    bool enabled;
    bool usage_power;
};

struct fan_gear {
    unsigned int duty;
    unsigned int rpm;
};

#define FAN_LEVELS 5

struct fan_context {
    uint8_t before_lock[0x20];
    struct mutex lock;
    uint8_t before_pwm[0x70 - 0x50];
    struct pwm_device *pwm;
    struct pwm_state pwm_state;
    uint8_t supported;
    uint8_t powered;
    uint8_t before_duty[6];
    unsigned int duty;
    uint8_t before_request[0xac - 0x9c];
    unsigned int requested_level;
    unsigned int debug_level;
    unsigned int target_rpm;
    unsigned int measured_rpm;
    unsigned int adjust_count;
    uint8_t before_gears[0x104 - 0xc0];
    struct fan_gear gears[FAN_LEVELS];
    uint8_t before_work[0x130 - 0x12c];
    struct delayed_work adjust_work;
};

_Static_assert(offsetof(struct module, init) == 0x188, "module init ABI");
_Static_assert(offsetof(struct module, exit) == 0x5b8, "module exit ABI");
_Static_assert(sizeof(struct module) == 0x600, "module size ABI");
_Static_assert(offsetof(struct device, driver_data) == 0x98, "driver data ABI");
_Static_assert(offsetof(struct device, mutex) == 0xa0, "device lock ABI");
_Static_assert(offsetof(struct kernel_param, arg) == 0x20, "parameter ABI");
_Static_assert(sizeof(struct kernel_param) == 0x28, "parameter size ABI");
_Static_assert(sizeof(struct pwm_state) == 0x18, "PWM state ABI");
_Static_assert(offsetof(struct fan_context, duty) == 0x98, "fan duty ABI");
_Static_assert(offsetof(struct fan_context, target_rpm) == 0xb4, "fan target ABI");
_Static_assert(offsetof(struct fan_context, gears) == 0x104, "fan table ABI");
_Static_assert(offsetof(struct fan_context, adjust_work) == 0x130, "fan work ABI");

extern struct bus_type platform_bus_type;
extern struct workqueue_struct *system_wq;
extern int device_match_name(struct device *device, const void *name);
extern struct device *bus_find_device(const struct bus_type *bus, struct device *start,
                                     const void *data, int (*match)(struct device *, const void *));
extern void put_device(struct device *device);
extern void mutex_lock(struct mutex *lock);
extern void mutex_unlock(struct mutex *lock);
extern int pwm_apply_state(struct pwm_device *pwm, const struct pwm_state *state);
extern bool queue_delayed_work_on(int cpu, struct workqueue_struct *queue,
                                 struct delayed_work *work, unsigned long delay);
extern int kstrtoint(const char *value, unsigned int base, int *result);
extern int scnprintf(char *buffer, size_t length, const char *format, ...);

#endif

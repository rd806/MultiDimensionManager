# MultiDimensionManager

Manage Minecraft world's dimensions, trying to remake the famous plugin [Multiverse-Core](https://modrinth.com/plugin/multiverse-core) in (Neo)Forge environment.

## Commands

### Manage dimensions

| Commands                                | Usage                               |
|-----------------------------------------|-------------------------------------|
| `/mdm dimension import <id> <source>`   | Import a world as a dimension       |
| `/mdm dimension create <id> <template>` | Create a dimension using a template |
| `/mdm dimension delete <id>`            | Delete a dimension                  |
| `/mdm dimension list`                   | Show all dimensions in the save     |

> In default, you should put all worlds in the folder `mdm_imports` in advance.

### Modify a dimension

| Commands                        | Usage                         |
|---------------------------------|-------------------------------|
| `/mdm modify spawn <x> <y> <z>` | Set a dimension's spawn point |
| `/mdm modify alias`             | Set a dimension's alias       |

### Teleport to a dimension

Teleport a player to a specific dimension, precise position is supported:

```
/mdm tp <target> <dimension> [<x> <y> <z>]
```

> If the position is not given, players will be teleported to the dimension's spawn point.

You can also use 

```
/mdm gui
```

to open a gui screen and teleport through a single mouse-click.

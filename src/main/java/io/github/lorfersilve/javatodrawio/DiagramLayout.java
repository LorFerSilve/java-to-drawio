package io.github.lorfersilve.javatodrawio;

import io.github.lorfersilve.javatodrawio.UmlModel.Relationship;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class DiagramLayout {
    private static final Spacing STANDARD = new Spacing(80, 120, 108);
    private static final Spacing COMPACT = new Spacing(54, 72, 62);

    private record Spacing(int margin, int columnGap, int rowGap) {
    }

    record Size(int width, int height) {
    }

    record Box(int row, int column, int x, int y, Size size) {
    }

    record Point(int x, int y) {
    }

    record Port(double x, double y) {
    }

    record Route(Port exit, Port entry, List<Point> points) {
        Route offset(int x, int y) {
            return new Route(exit, entry, points.stream().map(point -> new Point(point.x() + x, point.y() + y)).toList());
        }
    }

    record Grid(Map<String, Box> boxes, int[] rowTops, Spacing spacing) {
        int aboveRow(int row, int offset) {
            return rowTops[row] - spacing.rowGap() / 2 + offset;
        }

        int leftOf(Box box, int offset) {
            return box.x() - spacing.columnGap() / 2 + offset;
        }

        Route route(Relationship edge, int lane, int totalLanes) {
            Box source = boxes.get(edge.source());
            Box target = boxes.get(edge.target());
            if (source == target) {
                Port exit = new Port(1, 0.28);
                Port entry = new Port(1, 0.72);
                int x = source.x() + source.size().width() + 28;
                return new Route(exit, entry, List.of(
                        new Point(x, source.y() + (int) Math.round(exit.y() * source.size().height())),
                        new Point(x, source.y() + (int) Math.round(entry.y() * source.size().height()))));
            }
            double position = (lane + 1.0) / (totalLanes + 1.0);
            if (source.row() == target.row() && Math.abs(source.column() - target.column()) == 1) {
                int side = source.x() < target.x() ? 1 : 0;
                return new Route(new Port(side, position), new Port(1 - side, position), List.of());
            }
            boolean sameRow = source.row() == target.row();
            boolean down = source.row() < target.row();
            Port exit = new Port(position, down ? 1 : 0);
            Port entry = new Port(position, !sameRow && !down ? 1 : 0);

            int offset = laneOffset(edge);
            int sourceLane = aboveRow(source.row() + (down ? 1 : 0), offset);
            int targetLane = aboveRow(target.row() + (!sameRow && !down ? 1 : 0), offset);
            int sourceX = source.x() + (int) Math.round(position * source.size().width());
            int targetX = target.x() + (int) Math.round(position * target.size().width());
            List<Point> points = new ArrayList<>();
            points.add(new Point(sourceX, sourceLane));
            if (sourceLane != targetLane) {
                int columnLane = leftOf(source, offset);
                points.add(new Point(columnLane, sourceLane));
                points.add(new Point(columnLane, targetLane));
            }
            if (sourceX != targetX || sourceLane != targetLane) {
                points.add(new Point(targetX, targetLane));
            }
            return new Route(exit, entry, List.copyOf(points));
        }
    }

    static int laneOffset(Relationship edge) {
        int hash = 31 * (31 * edge.source().hashCode() + edge.target().hashCode()) + edge.kind().ordinal();
        return Math.floorMod(hash, 7) * 6 - 18;
    }

    static Grid place(Map<String, Size> sizes, List<Relationship> edges) {
        return place(sizes, edges, STANDARD);
    }

    static Grid placeCompact(Map<String, Size> sizes, List<Relationship> edges) {
        return place(sizes, edges, COMPACT);
    }

    private static Grid place(Map<String, Size> sizes, List<Relationship> edges, Spacing spacing) {
        if (sizes.isEmpty()) {
            return new Grid(Map.of(), new int[0], spacing);
        }
        List<String> order = connectedOrder(sizes.keySet(), edges);
        int columns = Math.min(8, (int) Math.ceil(Math.sqrt(order.size())));
        int rows = (order.size() + columns - 1) / columns;
        int[] columnWidths = new int[columns];
        int[] rowHeights = new int[rows];
        for (int i = 0; i < order.size(); i++) {
            Size size = sizes.get(order.get(i));
            int column = i % columns;
            int row = i / columns;
            columnWidths[column] = Math.max(columnWidths[column], size.width());
            rowHeights[row] = Math.max(rowHeights[row], size.height());
        }
        int[] columnStarts = starts(columnWidths, spacing.margin(), spacing.columnGap());
        int[] rowTops = starts(rowHeights, spacing.margin(), spacing.rowGap());
        Map<String, Box> boxes = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            String id = order.get(i);
            int column = i % columns;
            int row = i / columns;
            boxes.put(id, new Box(row, column, columnStarts[column], rowTops[row], sizes.get(id)));
        }
        return new Grid(boxes, rowTops, spacing);
    }

    private static int[] starts(int[] sizes, int margin, int gap) {
        int[] starts = new int[sizes.length];
        int next = margin;
        for (int i = 0; i < starts.length; i++) {
            starts[i] = next;
            next += sizes[i] + gap;
        }
        return starts;
    }

    private static List<String> connectedOrder(Set<String> ids, List<Relationship> edges) {
        Map<String, Set<String>> neighbors = new TreeMap<>();
        for (String id : ids) {
            neighbors.put(id, new TreeSet<>());
        }
        for (Relationship edge : edges) {
            neighbors.get(edge.source()).add(edge.target());
            neighbors.get(edge.target()).add(edge.source());
        }
        List<String> order = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String start : neighbors.keySet()) {
            if (!seen.add(start)) {
                continue;
            }
            queue.add(start);
            while (!queue.isEmpty()) {
                String id = queue.remove();
                order.add(id);
                for (String neighbor : neighbors.get(id)) {
                    if (seen.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        }
        return order;
    }

    private DiagramLayout() {
    }
}

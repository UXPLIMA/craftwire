export class RingBuffer<T> {
  private readonly items: T[] = [];

  constructor(private readonly capacity: number) {}

  push(value: T): void {
    this.items.push(value);
    if (this.items.length > this.capacity) this.items.shift();
  }

  toArray(): T[] {
    return [...this.items];
  }

  get size(): number {
    return this.items.length;
  }
}

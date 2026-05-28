# Module 02 — NestJS (TypeScript)

## 2.1 NestJS Architecture

NestJS is Angular-inspired: Modules organize code, Controllers handle HTTP, Providers (services) contain business logic, Guards protect routes, Interceptors transform responses, Pipes validate inputs, Filters handle exceptions.

```
Request
  │
  ▼
Middleware → Guard → Interceptor (pre) → Pipe → Controller → Interceptor (post) → Exception Filter
                                                                                       │
                                                                                  Response
```

## 2.2 Modules, Controllers, Providers

```typescript
// app.module.ts
@Module({
  imports: [ConfigModule.forRoot(), PrismaModule, KafkaModule],
  controllers: [NotificationController, HealthController],
  providers: [NotificationService, PushService, EmailService],
})
export class AppModule {}

// notification.controller.ts
@Controller("notifications")
export class NotificationController {
  constructor(private readonly service: NotificationService) {}

  @Post()
  @UseGuards(AuthGuard)
  async send(@Body() dto: SendNotificationDto) {
    return this.service.send(dto);
  }

  @Get(":id/status")
  async status(@Param("id", ParseUUIDPipe) id: string) {
    return this.service.getStatus(id);
  }
}

// notification.service.ts
@Injectable()
export class NotificationService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly push: PushService,
    private readonly email: EmailService,
  ) {}

  async send(dto: SendNotificationDto) {
    const notification = await this.prisma.notification.create({ data: dto });
    switch (dto.channel) {
      case "push": return this.push.send(notification);
      case "email": return this.email.send(notification);
    }
  }
}
```

## 2.3 Guards (Authentication)

```typescript
@Injectable()
export class AuthGuard implements CanActivate {
  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest();
    const token = request.headers.authorization?.replace("Bearer ", "");
    if (!token) throw new UnauthorizedException("Missing token");
    try {
      request.user = jwt.verify(token, PUBLIC_KEY, { algorithms: ["RS256"] });
      return true;
    } catch { throw new UnauthorizedException("Invalid token"); }
  }
}

// Role-based guard
@Injectable()
export class RolesGuard implements CanActivate {
  constructor(private reflector: Reflector) {}
  canActivate(context: ExecutionContext): boolean {
    const requiredRoles = this.reflector.get<string[]>("roles", context.getHandler());
    if (!requiredRoles) return true;
    const { user } = context.switchToHttp().getRequest();
    return requiredRoles.some(role => user.scopes?.includes(role));
  }
}

@SetMetadata("roles", ["write:payments"])
@Post() async createPayment() {}
```

## 2.4 Pipes (Validation)

```typescript
export class SendNotificationDto {
  @IsString() @IsNotEmpty() userId: string;
  @IsEnum(["push", "email", "sms"]) channel: string;
  @IsString() @MaxLength(200) title: string;
  @IsString() @MaxLength(2000) body: string;
}

// Apply globally
app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
```

## 2.5 Interceptors (Response Transformation)

```typescript
@Injectable()
export class TransformInterceptor<T> implements NestInterceptor<T, ApiResponse<T>> {
  intercept(context: ExecutionContext, next: CallHandler): Observable<ApiResponse<T>> {
    return next.handle().pipe(map(data => ({
      success: true, data, timestamp: new Date().toISOString(),
    })));
  }
}
```

## 2.6 Prisma Integration

```typescript
@Injectable()
export class PrismaService extends PrismaClient implements OnModuleInit {
  async onModuleInit() { await this.$connect(); }
  async onModuleDestroy() { await this.$disconnect(); }
}

// Using Prisma in a service
const notification = await this.prisma.notification.create({
  data: {
    userId: dto.userId,
    channel: dto.channel,
    title: dto.title,
    body: dto.body,
  },
});
```

## 2.7 Testing

```typescript
import { Test } from "@nestjs/testing";

describe("NotificationService", () => {
  let service: NotificationService;
  let prisma: PrismaService;

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [NotificationService, PrismaService],
    }).compile();
    service = module.get(NotificationService);
    prisma = module.get(PrismaService);
  });

  it("should send push notification", async () => {
    jest.spyOn(prisma.notification, "create").mockResolvedValue({ id: "1" } as any);
    const result = await service.send({ userId: "U1", channel: "push", title: "Payment", body: "100K" });
    expect(result.id).toBe("1");
  });
});
```

## 2.8 Exercises

### Ex 2.1 — CRUD Module
Create a `NotificationTemplate` module with CRUD endpoints. Use Prisma for persistence, ValidationPipe for validation, and global exception filter for errors.

### Ex 2.2 — Custom Guard + Decorator
Create a `@Scopes("write:payments")` decorator and a `ScopesGuard` that checks JWT scopes. Protect an endpoint. Test with valid scope, wrong scope, and no scope.

### Ex 2.3 — Interceptor Pipeline
Write a logging interceptor (log method + duration) and a response transform interceptor (wrap response in `{success, data, timestamp}`). Apply at controller and global level.

---

## 2.9 Self-Assessment

- [ ] Can create a NestJS module with controller, service, and Prisma integration
- [ ] Understand the execution order: Middleware → Guard → Interceptor → Pipe → Controller
- [ ] Can write a Guard for JWT authentication and RBAC
- [ ] Can use ValidationPipe with class-validator decorators

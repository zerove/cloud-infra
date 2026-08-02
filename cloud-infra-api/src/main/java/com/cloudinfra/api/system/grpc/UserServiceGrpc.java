package com.cloudinfra.api.system.grpc;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * 用户 gRPC 服务定义
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.65.1)",
    comments = "Source: src/main/proto/user.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class UserServiceGrpc {

  private UserServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "com.cloudinfra.api.system.grpc.UserService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.cloudinfra.api.system.grpc.GetUserByIdRequest,
      com.cloudinfra.api.system.grpc.GetUserByIdResponse> getGetUserByIdMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetUserById",
      requestType = com.cloudinfra.api.system.grpc.GetUserByIdRequest.class,
      responseType = com.cloudinfra.api.system.grpc.GetUserByIdResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.cloudinfra.api.system.grpc.GetUserByIdRequest,
      com.cloudinfra.api.system.grpc.GetUserByIdResponse> getGetUserByIdMethod() {
    io.grpc.MethodDescriptor<com.cloudinfra.api.system.grpc.GetUserByIdRequest, com.cloudinfra.api.system.grpc.GetUserByIdResponse> getGetUserByIdMethod;
    if ((getGetUserByIdMethod = UserServiceGrpc.getGetUserByIdMethod) == null) {
      synchronized (UserServiceGrpc.class) {
        if ((getGetUserByIdMethod = UserServiceGrpc.getGetUserByIdMethod) == null) {
          UserServiceGrpc.getGetUserByIdMethod = getGetUserByIdMethod =
              io.grpc.MethodDescriptor.<com.cloudinfra.api.system.grpc.GetUserByIdRequest, com.cloudinfra.api.system.grpc.GetUserByIdResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetUserById"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.cloudinfra.api.system.grpc.GetUserByIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.cloudinfra.api.system.grpc.GetUserByIdResponse.getDefaultInstance()))
              .setSchemaDescriptor(new UserServiceMethodDescriptorSupplier("GetUserById"))
              .build();
        }
      }
    }
    return getGetUserByIdMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.cloudinfra.api.system.grpc.GetUserByUsernameRequest,
      com.cloudinfra.api.system.grpc.GetUserByUsernameResponse> getGetUserByUsernameMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetUserByUsername",
      requestType = com.cloudinfra.api.system.grpc.GetUserByUsernameRequest.class,
      responseType = com.cloudinfra.api.system.grpc.GetUserByUsernameResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.cloudinfra.api.system.grpc.GetUserByUsernameRequest,
      com.cloudinfra.api.system.grpc.GetUserByUsernameResponse> getGetUserByUsernameMethod() {
    io.grpc.MethodDescriptor<com.cloudinfra.api.system.grpc.GetUserByUsernameRequest, com.cloudinfra.api.system.grpc.GetUserByUsernameResponse> getGetUserByUsernameMethod;
    if ((getGetUserByUsernameMethod = UserServiceGrpc.getGetUserByUsernameMethod) == null) {
      synchronized (UserServiceGrpc.class) {
        if ((getGetUserByUsernameMethod = UserServiceGrpc.getGetUserByUsernameMethod) == null) {
          UserServiceGrpc.getGetUserByUsernameMethod = getGetUserByUsernameMethod =
              io.grpc.MethodDescriptor.<com.cloudinfra.api.system.grpc.GetUserByUsernameRequest, com.cloudinfra.api.system.grpc.GetUserByUsernameResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetUserByUsername"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.cloudinfra.api.system.grpc.GetUserByUsernameRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.cloudinfra.api.system.grpc.GetUserByUsernameResponse.getDefaultInstance()))
              .setSchemaDescriptor(new UserServiceMethodDescriptorSupplier("GetUserByUsername"))
              .build();
        }
      }
    }
    return getGetUserByUsernameMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.cloudinfra.api.system.grpc.GetPermissionsByUserIdRequest,
      com.cloudinfra.api.system.grpc.GetPermissionsByUserIdResponse> getGetPermissionsByUserIdMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetPermissionsByUserId",
      requestType = com.cloudinfra.api.system.grpc.GetPermissionsByUserIdRequest.class,
      responseType = com.cloudinfra.api.system.grpc.GetPermissionsByUserIdResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.cloudinfra.api.system.grpc.GetPermissionsByUserIdRequest,
      com.cloudinfra.api.system.grpc.GetPermissionsByUserIdResponse> getGetPermissionsByUserIdMethod() {
    io.grpc.MethodDescriptor<com.cloudinfra.api.system.grpc.GetPermissionsByUserIdRequest, com.cloudinfra.api.system.grpc.GetPermissionsByUserIdResponse> getGetPermissionsByUserIdMethod;
    if ((getGetPermissionsByUserIdMethod = UserServiceGrpc.getGetPermissionsByUserIdMethod) == null) {
      synchronized (UserServiceGrpc.class) {
        if ((getGetPermissionsByUserIdMethod = UserServiceGrpc.getGetPermissionsByUserIdMethod) == null) {
          UserServiceGrpc.getGetPermissionsByUserIdMethod = getGetPermissionsByUserIdMethod =
              io.grpc.MethodDescriptor.<com.cloudinfra.api.system.grpc.GetPermissionsByUserIdRequest, com.cloudinfra.api.system.grpc.GetPermissionsByUserIdResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetPermissionsByUserId"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.cloudinfra.api.system.grpc.GetPermissionsByUserIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.cloudinfra.api.system.grpc.GetPermissionsByUserIdResponse.getDefaultInstance()))
              .setSchemaDescriptor(new UserServiceMethodDescriptorSupplier("GetPermissionsByUserId"))
              .build();
        }
      }
    }
    return getGetPermissionsByUserIdMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.cloudinfra.api.system.grpc.RegisterUserRequest,
      com.cloudinfra.api.system.grpc.RegisterUserResponse> getRegisterUserMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RegisterUser",
      requestType = com.cloudinfra.api.system.grpc.RegisterUserRequest.class,
      responseType = com.cloudinfra.api.system.grpc.RegisterUserResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.cloudinfra.api.system.grpc.RegisterUserRequest,
      com.cloudinfra.api.system.grpc.RegisterUserResponse> getRegisterUserMethod() {
    io.grpc.MethodDescriptor<com.cloudinfra.api.system.grpc.RegisterUserRequest, com.cloudinfra.api.system.grpc.RegisterUserResponse> getRegisterUserMethod;
    if ((getRegisterUserMethod = UserServiceGrpc.getRegisterUserMethod) == null) {
      synchronized (UserServiceGrpc.class) {
        if ((getRegisterUserMethod = UserServiceGrpc.getRegisterUserMethod) == null) {
          UserServiceGrpc.getRegisterUserMethod = getRegisterUserMethod =
              io.grpc.MethodDescriptor.<com.cloudinfra.api.system.grpc.RegisterUserRequest, com.cloudinfra.api.system.grpc.RegisterUserResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RegisterUser"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.cloudinfra.api.system.grpc.RegisterUserRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.cloudinfra.api.system.grpc.RegisterUserResponse.getDefaultInstance()))
              .setSchemaDescriptor(new UserServiceMethodDescriptorSupplier("RegisterUser"))
              .build();
        }
      }
    }
    return getRegisterUserMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static UserServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<UserServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<UserServiceStub>() {
        @java.lang.Override
        public UserServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new UserServiceStub(channel, callOptions);
        }
      };
    return UserServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static UserServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<UserServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<UserServiceBlockingStub>() {
        @java.lang.Override
        public UserServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new UserServiceBlockingStub(channel, callOptions);
        }
      };
    return UserServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static UserServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<UserServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<UserServiceFutureStub>() {
        @java.lang.Override
        public UserServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new UserServiceFutureStub(channel, callOptions);
        }
      };
    return UserServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * 用户 gRPC 服务定义
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * 根据 ID 获取用户
     * </pre>
     */
    default void getUserById(com.cloudinfra.api.system.grpc.GetUserByIdRequest request,
        io.grpc.stub.StreamObserver<com.cloudinfra.api.system.grpc.GetUserByIdResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetUserByIdMethod(), responseObserver);
    }

    /**
     * <pre>
     * 根据用户名获取用户
     * </pre>
     */
    default void getUserByUsername(com.cloudinfra.api.system.grpc.GetUserByUsernameRequest request,
        io.grpc.stub.StreamObserver<com.cloudinfra.api.system.grpc.GetUserByUsernameResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetUserByUsernameMethod(), responseObserver);
    }

    /**
     * <pre>
     * 根据用户 ID 获取权限列表
     * </pre>
     */
    default void getPermissionsByUserId(com.cloudinfra.api.system.grpc.GetPermissionsByUserIdRequest request,
        io.grpc.stub.StreamObserver<com.cloudinfra.api.system.grpc.GetPermissionsByUserIdResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetPermissionsByUserIdMethod(), responseObserver);
    }

    /**
     * <pre>
     * 注册用户
     * </pre>
     */
    default void registerUser(com.cloudinfra.api.system.grpc.RegisterUserRequest request,
        io.grpc.stub.StreamObserver<com.cloudinfra.api.system.grpc.RegisterUserResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRegisterUserMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service UserService.
   * <pre>
   * 用户 gRPC 服务定义
   * </pre>
   */
  public static abstract class UserServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return UserServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service UserService.
   * <pre>
   * 用户 gRPC 服务定义
   * </pre>
   */
  public static final class UserServiceStub
      extends io.grpc.stub.AbstractAsyncStub<UserServiceStub> {
    private UserServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected UserServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new UserServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * 根据 ID 获取用户
     * </pre>
     */
    public void getUserById(com.cloudinfra.api.system.grpc.GetUserByIdRequest request,
        io.grpc.stub.StreamObserver<com.cloudinfra.api.system.grpc.GetUserByIdResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetUserByIdMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 根据用户名获取用户
     * </pre>
     */
    public void getUserByUsername(com.cloudinfra.api.system.grpc.GetUserByUsernameRequest request,
        io.grpc.stub.StreamObserver<com.cloudinfra.api.system.grpc.GetUserByUsernameResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetUserByUsernameMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 根据用户 ID 获取权限列表
     * </pre>
     */
    public void getPermissionsByUserId(com.cloudinfra.api.system.grpc.GetPermissionsByUserIdRequest request,
        io.grpc.stub.StreamObserver<com.cloudinfra.api.system.grpc.GetPermissionsByUserIdResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetPermissionsByUserIdMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 注册用户
     * </pre>
     */
    public void registerUser(com.cloudinfra.api.system.grpc.RegisterUserRequest request,
        io.grpc.stub.StreamObserver<com.cloudinfra.api.system.grpc.RegisterUserResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRegisterUserMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service UserService.
   * <pre>
   * 用户 gRPC 服务定义
   * </pre>
   */
  public static final class UserServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<UserServiceBlockingStub> {
    private UserServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected UserServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new UserServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * 根据 ID 获取用户
     * </pre>
     */
    public com.cloudinfra.api.system.grpc.GetUserByIdResponse getUserById(com.cloudinfra.api.system.grpc.GetUserByIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetUserByIdMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 根据用户名获取用户
     * </pre>
     */
    public com.cloudinfra.api.system.grpc.GetUserByUsernameResponse getUserByUsername(com.cloudinfra.api.system.grpc.GetUserByUsernameRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetUserByUsernameMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 根据用户 ID 获取权限列表
     * </pre>
     */
    public com.cloudinfra.api.system.grpc.GetPermissionsByUserIdResponse getPermissionsByUserId(com.cloudinfra.api.system.grpc.GetPermissionsByUserIdRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetPermissionsByUserIdMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 注册用户
     * </pre>
     */
    public com.cloudinfra.api.system.grpc.RegisterUserResponse registerUser(com.cloudinfra.api.system.grpc.RegisterUserRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRegisterUserMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service UserService.
   * <pre>
   * 用户 gRPC 服务定义
   * </pre>
   */
  public static final class UserServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<UserServiceFutureStub> {
    private UserServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected UserServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new UserServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * 根据 ID 获取用户
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.cloudinfra.api.system.grpc.GetUserByIdResponse> getUserById(
        com.cloudinfra.api.system.grpc.GetUserByIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetUserByIdMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 根据用户名获取用户
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.cloudinfra.api.system.grpc.GetUserByUsernameResponse> getUserByUsername(
        com.cloudinfra.api.system.grpc.GetUserByUsernameRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetUserByUsernameMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 根据用户 ID 获取权限列表
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.cloudinfra.api.system.grpc.GetPermissionsByUserIdResponse> getPermissionsByUserId(
        com.cloudinfra.api.system.grpc.GetPermissionsByUserIdRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetPermissionsByUserIdMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 注册用户
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.cloudinfra.api.system.grpc.RegisterUserResponse> registerUser(
        com.cloudinfra.api.system.grpc.RegisterUserRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRegisterUserMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_USER_BY_ID = 0;
  private static final int METHODID_GET_USER_BY_USERNAME = 1;
  private static final int METHODID_GET_PERMISSIONS_BY_USER_ID = 2;
  private static final int METHODID_REGISTER_USER = 3;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_USER_BY_ID:
          serviceImpl.getUserById((com.cloudinfra.api.system.grpc.GetUserByIdRequest) request,
              (io.grpc.stub.StreamObserver<com.cloudinfra.api.system.grpc.GetUserByIdResponse>) responseObserver);
          break;
        case METHODID_GET_USER_BY_USERNAME:
          serviceImpl.getUserByUsername((com.cloudinfra.api.system.grpc.GetUserByUsernameRequest) request,
              (io.grpc.stub.StreamObserver<com.cloudinfra.api.system.grpc.GetUserByUsernameResponse>) responseObserver);
          break;
        case METHODID_GET_PERMISSIONS_BY_USER_ID:
          serviceImpl.getPermissionsByUserId((com.cloudinfra.api.system.grpc.GetPermissionsByUserIdRequest) request,
              (io.grpc.stub.StreamObserver<com.cloudinfra.api.system.grpc.GetPermissionsByUserIdResponse>) responseObserver);
          break;
        case METHODID_REGISTER_USER:
          serviceImpl.registerUser((com.cloudinfra.api.system.grpc.RegisterUserRequest) request,
              (io.grpc.stub.StreamObserver<com.cloudinfra.api.system.grpc.RegisterUserResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getGetUserByIdMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.cloudinfra.api.system.grpc.GetUserByIdRequest,
              com.cloudinfra.api.system.grpc.GetUserByIdResponse>(
                service, METHODID_GET_USER_BY_ID)))
        .addMethod(
          getGetUserByUsernameMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.cloudinfra.api.system.grpc.GetUserByUsernameRequest,
              com.cloudinfra.api.system.grpc.GetUserByUsernameResponse>(
                service, METHODID_GET_USER_BY_USERNAME)))
        .addMethod(
          getGetPermissionsByUserIdMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.cloudinfra.api.system.grpc.GetPermissionsByUserIdRequest,
              com.cloudinfra.api.system.grpc.GetPermissionsByUserIdResponse>(
                service, METHODID_GET_PERMISSIONS_BY_USER_ID)))
        .addMethod(
          getRegisterUserMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.cloudinfra.api.system.grpc.RegisterUserRequest,
              com.cloudinfra.api.system.grpc.RegisterUserResponse>(
                service, METHODID_REGISTER_USER)))
        .build();
  }

  private static abstract class UserServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    UserServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.cloudinfra.api.system.grpc.UserServiceProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("UserService");
    }
  }

  private static final class UserServiceFileDescriptorSupplier
      extends UserServiceBaseDescriptorSupplier {
    UserServiceFileDescriptorSupplier() {}
  }

  private static final class UserServiceMethodDescriptorSupplier
      extends UserServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    UserServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (UserServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new UserServiceFileDescriptorSupplier())
              .addMethod(getGetUserByIdMethod())
              .addMethod(getGetUserByUsernameMethod())
              .addMethod(getGetPermissionsByUserIdMethod())
              .addMethod(getRegisterUserMethod())
              .build();
        }
      }
    }
    return result;
  }
}

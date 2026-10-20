.method public b(Lcom/roblox/client/d$d;)V
    .locals 2

    iget-object v0, p0, Lcom/roblox/client/d;->a:Lcom/roblox/client/d$e;

    sget-object v1, Lcom/roblox/client/d$f;->d:Lcom/roblox/client/d$f;

    iput-object v1, v0, Lcom/roblox/client/d$e;->a:Lcom/roblox/client/d$f;

    if-eqz p1, :cond_0

    invoke-interface {p1, v0}, Lcom/roblox/client/d$d;->a(Lcom/roblox/client/d$e;)V

    :cond_0
    return-void
.end method

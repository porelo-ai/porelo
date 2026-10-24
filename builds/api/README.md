# api

The contract between Porelo and a build reader, published as `porelo-build-api`: the interface a reader implements, the server it runs and the client Porelo uses, over the varlink protocol `org.fuin.porelo.Build2`. Its `build` operation returns a commit's verdict and jobs, with logs selected as `none`, `failure` or `all`. It reads no forge itself. Writing a reader is in [writing a build reader](../../doc/build-readers.md).

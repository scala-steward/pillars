// Copyright (c) 2024-2024 by Raphaël Lemaitre and Contributors
// This software is licensed under the Eclipse Public License v2.0 (EPL-2.0).
// For more information see LICENSE or https://opensource.org/license/epl-2-0

package pillars

import scala.annotation.targetName

object syntax:
    object all:
        export api.*
        export language.*

    object language:
        extension [A](a: A)
            @targetName("pipe")
            inline def |>[B](f: A => B): B = f(a)

    object api:
        extension [A](maybeA: Option[A])
            inline def toRightWithError(e: PillarsError): Either[HttpErrorResponse, A] =
                maybeA match
                    case Some(value) => Right(value)
                    case None        => e.httpResponse
            end toRightWithError
        end extension
    end api

end syntax
